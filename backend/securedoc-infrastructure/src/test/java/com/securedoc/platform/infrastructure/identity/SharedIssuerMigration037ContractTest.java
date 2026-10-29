package com.securedoc.platform.infrastructure.identity;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Static guard for the staged PostgreSQL R1B migration before registration. */
class SharedIssuerMigration037ContractTest {
    @Test
    void migrationMaintainsPerTenantUniquenessAndValidDollarQuotePairs() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "db/changelog/037-r1b-shared-issuer-active-bindings.xml")) {
            assertNotNull(stream, "R1B staged migration must be present");
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(2, xml.split("do \\$r1b\\$", -1).length - 1,
                    "Both DO blocks must use named PostgreSQL dollar-quote delimiters");
            assertEquals(2, xml.split("end \\$r1b\\$;", -1).length - 1,
                    "Both DO blocks must be properly closed");
            assertTrue(xml.contains("uq_eic_active_per_tenant"));
            assertTrue(xml.contains("end $r1b$;\n      </sql>\n      <sql>\n        create unique index uq_eic_active_binding"),
                    "Rollback must recreate the index in a separate SQL statement");
            assertTrue(xml.contains("raise exception 'Cannot restore legacy ACTIVE binding uniqueness"));
            assertTrue(xml.contains("splitStatements=\"false\""));
        }
    }
}
