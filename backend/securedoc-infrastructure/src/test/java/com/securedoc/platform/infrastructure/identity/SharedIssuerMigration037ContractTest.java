package com.securedoc.platform.infrastructure.identity;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Static rollout contract for the staged PostgreSQL R1B migration. */
class SharedIssuerMigration037ContractTest {
    private static final String MIGRATION = "db/changelog/037-r1b-shared-issuer-active-bindings.xml";

    @Test
    void migrationKeepsPerTenantUniquenessAndRollsBackWithACollisionGuard() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setExpandEntityReferences(false);
        try (var stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertNotNull(stream, "Staged migration must be on the classpath");
            var xml = factory.newDocumentBuilder().parse(stream);
            var changes = xml.getElementsByTagName("changeSet");
            assertEquals(1, changes.getLength());
            var change = (Element) changes.item(0);
            assertEquals("037-r1b-shared-issuer-active-bindings", change.getAttribute("id"));
            var forward = (Element) change.getElementsByTagName("sql").item(0);
            assertEquals("false", forward.getAttribute("splitStatements"));
            assertTrue(forward.getTextContent().contains("uq_eic_active_per_tenant"));
            assertTrue(forward.getTextContent().contains("drop index uq_eic_active_binding"));
            assertEquals(1, count(forward.getTextContent(), "do $r1b$"));
            assertEquals(1, count(forward.getTextContent(), "end $r1b$;"));

            var rollback = (Element) change.getElementsByTagName("rollback").item(0);
            assertNotNull(rollback);
            NodeList rollbackSql = rollback.getElementsByTagName("sql");
            assertEquals(2, rollbackSql.getLength(), "Rollback must check conflicts before index creation");
            var guard = (Element) rollbackSql.item(0);
            var restore = (Element) rollbackSql.item(1);
            assertEquals("false", guard.getAttribute("splitStatements"));
            assertEquals(1, count(guard.getTextContent(), "do $r1b$"));
            assertEquals(1, count(guard.getTextContent(), "end $r1b$;"));
            assertTrue(guard.getTextContent().contains("having count(*) > 1"));
            assertTrue(guard.getTextContent().contains("raise exception"));
            assertTrue(restore.getTextContent().contains("create unique index uq_eic_active_binding"));
            assertTrue(restore.getTextContent().contains("where status='ACTIVE'"));
        }
    }

    @Test
    void migrationIsNotRegisteredBeforeRuntimeAcceptance() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "db/changelog/db.changelog-master.xml")) {
            assertNotNull(stream, "Master changelog must be available");
            String master = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(master.contains("037-r1b-shared-issuer-active-bindings.xml"));
            assertTrue(master.contains("034-enterprise-identity-activation.xml"));
        }
    }

    private static int count(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
