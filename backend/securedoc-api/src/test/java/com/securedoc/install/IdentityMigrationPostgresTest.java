package com.securedoc.install;

import com.documentplatform.identity.jdbc.PostgresGroupGrantStore;
import com.documentplatform.identity.jdbc.PostgresLoginConnectionStore;
import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.LoginConnection;
import com.securedoc.platform.infrastructure.identity.LexaVaultProduct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0037 step 4 on PostgreSQL: the one-off migration from enterprise_identity_connections and
 * tenant_oidc_group_grants into the idn_ tables. Idempotent, reports counts, prints no secret. Throwaway server only.
 */
@EnabledIfEnvironmentVariable(named = ThrowawayDatabase.URL_VARIABLE, matches = "jdbc:postgresql:.*")
class IdentityMigrationPostgresTest {

    private static final String TENANT = "TEN-MIG-0001";
    private static final URI ISSUER = URI.create("https://login.mig.example/oauth2/default");
    private static final String SECRET_REFERENCE = "vault:kv/customers/TEN-MIG-0001/login#client_secret";

    private ThrowawayDatabase db;
    private IdnRows rows;
    private UUID a, b;
    private final UUID legacy = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() throws SQLException {
        db = ThrowawayDatabase.create("mig");
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertEquals(0, new SchemaStep(quiet, quiet).run(new String[] {"apply"}, db.env(SchemaStep.ENV_PREFIX)));
        rows = new IdnRows(db);
        // What setup stores: the customer and its slots. The app's entity rows use the derived entity UUIDs.
        rows.customer(TENANT);
        a = rows.slot(TENANT, "ENT-MIG-A", "ACTIVE");
        b = rows.slot(TENANT, "ENT-MIG-B", "ACTIVE");
        rows.lexaTenant(a, "ACTIVE");
        rows.lexaTenant(b, "ACTIVE");
        rows.lexaTenant(legacy, "ACTIVE");
        // The old tables, as an R1/R1B install has them: one ACTIVE connection per issuer key (migration 037 is not
        // registered), here for entity A; entity B has grants only; the legacy entity has its own audience.
        oldConnection(a, "ACTIVE", "api://lexavault-mig");
        oldConnection(legacy, "ACTIVE", "api://legacy");
        oldConnection(a, "DISABLED", "api://older");
        oldGrant(a, "Finance Staff", "DOCUMENT_SUPERVISOR");
        oldGrant(a, "Finance Admins", "BUSINESS_ADMIN");
        oldGrant(b, "Legal Staff", "DOCUMENT_PROCESSOR");
        oldGrant(b, "Ops", "PLATFORM_OPERATIONS_ADMIN");
        oldGrant(legacy, "Legacy Staff", "DOCUMENT_PROCESSOR");
    }

    @AfterEach
    void drop() throws SQLException {
        if (db != null) db.close();
    }

    private void oldConnection(UUID tenant, String status, String audience) throws SQLException {
        rows.exec("""
                insert into enterprise_identity_connections (connection_id, tenant_id, provider_id, provider_type,
                    display_name, issuer_uri, browser_client_id, api_audience, secret_reference, status,
                    configuration_version, created_at, updated_at)
                values (?, ?, 'mig-okta', 'OIDC', 'Okta', ?, 'mig-web', ?, ?, ?, 1, now(), now())
                """, UUID.randomUUID(), tenant, ISSUER.toString(), audience, SECRET_REFERENCE, status);
    }

    private void oldGrant(UUID tenant, String group, String role) throws SQLException {
        rows.exec("insert into tenant_oidc_group_grants (tenant_id, issuer_uri, external_group, product_role) "
                + "values (?, ?, ?, ?)", tenant, ISSUER.toString(), group, role);
    }

    private record Run(int exit, String output) {
    }

    private Run run(String command) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        int exit = new IdentityMigration(print, print).run(new String[] {command}, db.env(IdentityMigration.ENV_PREFIX));
        String text = out.toString(StandardCharsets.UTF_8);
        assertFalse(text.contains(db.password), "the password is never printed");
        assertFalse(text.contains(SECRET_REFERENCE), "the secret reference is never printed");
        return new Run(exit, text);
    }

    @Test
    void planChangesNothingApplyCopiesAndARerunCopiesNothingMore() throws SQLException {
        Run plan = run("plan");
        assertEquals(0, plan.exit(), plan.output());
        assertTrue(plan.output().contains("connections created=1 unchanged=0 conflict=0; grants added=3 unchanged=0 "
                + "not_copied=1; entities skipped=1"), plan.output());
        assertEquals(0, count("select count(*) from idn_login_connection"));
        assertEquals(0, count("select count(*) from idn_group_grant"));

        Run apply = run("apply");
        assertEquals(0, apply.exit(), apply.output());
        assertTrue(apply.output().contains("skipped     entity " + legacy), apply.output());
        LoginConnection stored = new PostgresLoginConnectionStore(rows.ds).active(TENANT, LexaVaultProduct.CODE).orElseThrow();
        assertEquals("mig-okta", stored.providerId());
        assertEquals(ISSUER, stored.issuer());
        assertEquals("api://lexavault-mig", stored.apiAudience());
        assertEquals("mig-web", stored.browserClientId());
        assertEquals(SECRET_REFERENCE, stored.secretReference());
        assertEquals(ChangeSource.MIGRATION, new PostgresLoginConnectionStore(rows.ds).history(stored.connectionId()).get(0).source());
        PostgresGroupGrantStore grants = new PostgresGroupGrantStore(rows.ds);
        assertEquals(Set.of(new Grant("Finance Staff", "DOCUMENT_SUPERVISOR"), new Grant("Finance Admins", "BUSINESS_ADMIN")),
                grants.grants("ENT-MIG-A", LexaVaultProduct.CODE, ISSUER));
        assertEquals(Set.of(new Grant("Legal Staff", "DOCUMENT_PROCESSOR")), grants.grants("ENT-MIG-B", LexaVaultProduct.CODE, ISSUER));
        // The old tables are left as they were.
        assertEquals(3, count("select count(*) from enterprise_identity_connections"));
        assertEquals(5, count("select count(*) from tenant_oidc_group_grants"));

        Run again = run("apply");
        assertEquals(0, again.exit(), again.output());
        assertTrue(again.output().contains("connections created=0 unchanged=1 conflict=0; grants added=0 unchanged=3"), again.output());
        assertEquals(1, count("select count(*) from idn_login_connection"));
        assertEquals(1, count("select count(*) from idn_login_connection_history"));
        assertEquals(3, count("select count(*) from idn_group_grant"));
    }

    @Test
    void entitiesWhoseConnectionsDifferAreAConflictAndNoConnectionIsCopied() throws SQLException {
        oldConnection(b, "ACTIVE", "api://b-only");
        Run apply = run("apply");
        assertEquals(3, apply.exit(), apply.output());
        assertTrue(apply.output().contains("conflict    login connection: the entities' ACTIVE connections differ"), apply.output());
        assertEquals(0, count("select count(*) from idn_login_connection"));
        assertEquals(3, count("select count(*) from idn_group_grant"), "the grants are still copied");
    }

    @Test
    void aConnectionAlreadyStoredWithOtherValuesIsKept() throws SQLException {
        rows.connection(TENANT, "setup-okta", ISSUER, "api://from-setup");
        Run apply = run("apply");
        assertEquals(3, apply.exit(), apply.output());
        assertTrue(apply.output().contains("idn_ already holds another ACTIVE connection; it is kept"), apply.output());
        assertEquals("api://from-setup", new PostgresLoginConnectionStore(rows.ds).active(TENANT, LexaVaultProduct.CODE)
                .orElseThrow().apiAudience());
    }

    @Test
    void withoutSetupNothingIsCopiedAndTheReasonIsNamed() throws SQLException {
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            s.execute("delete from idn_entity");
            s.execute("delete from idn_customer");
        }
        Run apply = run("apply");
        assertEquals(2, apply.exit());
        assertTrue(apply.output().contains("NO_CUSTOMER: no customer is stored: run setup first"), apply.output());
        assertEquals(2, new IdentityMigration(new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()))
                .run(new String[] {"copy"}, db.env(IdentityMigration.ENV_PREFIX)));
    }

    private long count(String sql) throws SQLException {
        try (Connection c = db.connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
