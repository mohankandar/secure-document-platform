package com.securedoc.install;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0037 step 3 on PostgreSQL: the schema step on a fresh database, a rerun, the runtime user's rights. Runs only
 * when {@code LEXAVAULT_TEST_PG_URL}, {@code _USER} and {@code _PASSWORD} point at a throwaway server; each run makes
 * and drops its own database there. The normal build skips it.
 */
@EnabledIfEnvironmentVariable(named = ThrowawayDatabase.URL_VARIABLE, matches = "jdbc:postgresql:.*")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaStepPostgresTest {

    private static ThrowawayDatabase db;
    private static final String RUNTIME_USER = "lv_rt_" + UUID.randomUUID().toString().substring(0, 8);
    private static final String RUNTIME_PASSWORD = UUID.randomUUID().toString();

    @BeforeAll
    static void create() throws SQLException {
        db = ThrowawayDatabase.create("schema");
        db.onServer("create role " + RUNTIME_USER + " login password '" + RUNTIME_PASSWORD + "'");
    }

    @AfterAll
    static void drop() throws SQLException {
        if (db != null) {
            db.close();
            db.onServer("drop role if exists " + RUNTIME_USER);
        }
    }

    private static String run(int expectedExit, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        int exit = new SchemaStep(print, print).run(args, db.env(SchemaStep.ENV_PREFIX));
        String text = out.toString(StandardCharsets.UTF_8);
        assertEquals(expectedExit, exit, text);
        assertFalse(text.contains(db.password), "the password is never printed");
        return text;
    }

    @Test
    @Order(1)
    void aFreshDatabaseGetsEveryRunEachWithItsOwnHistoryAndARerunChangesNothing() throws SQLException {
        run(2, "customer-installed");  // before the schema step: the identity tables are missing
        String first = run(0, "apply", "--runtime-user", RUNTIME_USER);
        // platform-core 0.7.0 (task 0054): identity V5, licensing V2.
        assertTrue(first.contains("identity         5 migration(s) run, at version 5"), first);
        assertTrue(first.contains("licensing        2 migration(s) run, at version 2"), first);
        try (Connection c = db.connect()) {
            assertEquals(5, count(c, "select count(*) from idn_schema_history where version in ('1','2','3','4','5') and success"));
            assertEquals(2, count(c, "select count(*) from lic_schema_history where version in ('1','2') and success"));
            assertEquals(5, count(c, "select max(version) from idn_schema_version"));
            assertEquals(1, count(c, "select count(*) from information_schema.tables where table_name = 'idn_platform_grant'"));
            assertEquals(1, count(c, "select count(*) from information_schema.tables where table_name = 'idn_machine_binding'"));
            assertEquals(1, count(c, "select count(*) from information_schema.tables where table_name = 'idn_grant_history'"));
            assertEquals(2, count(c, "select max(version) from lic_schema_version"));
            assertEquals(1, count(c, "select count(*) from information_schema.tables where table_name = 'lic_version_floor'"));
            assertTrue(count(c, "select count(*) from databasechangelog") > 90, "the app's changelog ran");
            assertEquals(1, count(c, "select count(*) from information_schema.tables where table_name = 'tenants'"));
        }
        String second = run(0, "apply");
        assertTrue(second.contains("identity         0 migration(s) run, at version 5"), second);
        assertTrue(second.contains("licensing        0 migration(s) run, at version 2"), second);
        String status = run(0, "status");
        assertTrue(status.contains("identity         version 5") && status.contains("licensing        version 2"), status);
        assertEquals(4, exitOf("customer-installed"));
    }

    @Test
    @Order(2)
    void theRuntimeUserGetsDmlOnThePlatformTablesAndNothingMore() throws SQLException {
        run(0, "apply", "--runtime-user", RUNTIME_USER);
        try (Connection c = db.connectAs(RUNTIME_USER, RUNTIME_PASSWORD); Statement s = c.createStatement()) {
            s.execute("insert into idn_customer (tenant_id, name) values ('TEN-RT-CHECK', 'Check')");
            s.execute("update idn_customer set name = 'Checked' where tenant_id = 'TEN-RT-CHECK'");
            s.execute("delete from idn_customer where tenant_id = 'TEN-RT-CHECK'");
            s.execute("select count(*) from lic_licence");
            s.execute("select version from idn_schema_version");
            s.execute("select count(*) from idn_machine_binding");
            // Task 0041: the Audit page reads the grant history; it is insert-only, even for the table's owner.
            s.execute("insert into idn_grant_history (tenant_id, product_code, slot_id, entity_uuid, issuer_uri, "
                    + "external_group, role_after, change, change_source, changed_by) values ('TEN-RT-CHECK', 'LEXAVAULT', "
                    + "'ENT-RT-CHECK', '" + java.util.UUID.randomUUID() + "', 'https://login.example', 'G', "
                    + "'REPORT_VIEWER', 'ADDED', 'APP', 'app')");
            s.execute("select count(*) from idn_grant_history");
            assertThrows(SQLException.class, () -> s.execute("update idn_grant_history set changed_by = 'x'"));
            assertThrows(SQLException.class, () -> s.execute("delete from idn_grant_history"));
            assertThrows(SQLException.class, () -> s.execute("update idn_schema_version set version = 6"));
            assertThrows(SQLException.class, () -> s.execute("delete from idn_schema_history"));
            assertThrows(SQLException.class, () -> s.execute("create table lv_not_allowed (id int)"));
        }
    }

    /** Task 0054: exactly the rights of platform-core 0.7.0's install guides, table by table, and nothing on the rest. */
    @Test
    @Order(2)
    void theRuntimeUsersRightsAreExactlyTheOnesPlatformCoreLists() throws SQLException {
        run(0, "apply", "--runtime-user", RUNTIME_USER);
        assertRights(db, Map.ofEntries(
                Map.entry("idn_customer", DML), Map.entry("idn_entity", DML), Map.entry("idn_login_connection", DML),
                Map.entry("idn_group_grant", DML), Map.entry("idn_machine_binding", DML), Map.entry("idn_platform_grant", DML),
                Map.entry("idn_grant_history", "INSERT,SELECT"), Map.entry("idn_login_connection_history", "INSERT,SELECT"),
                Map.entry("idn_machine_binding_history", "INSERT,SELECT"), Map.entry("idn_schema_version", "SELECT"),
                Map.entry("lic_licence", DML), Map.entry("lic_usage", DML), Map.entry("lic_chain_head", DML),
                Map.entry("lic_notice_sent", DML), Map.entry("lic_last_seen", "INSERT,SELECT,UPDATE"),
                Map.entry("lic_clock_reset", "INSERT,SELECT"), Map.entry("lic_version_floor", "SELECT"),
                Map.entry("lic_schema_version", "SELECT")));
        // platform-core's own start-up checks pass as this user.
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(db.url, RUNTIME_USER, RUNTIME_PASSWORD);
        com.documentplatform.identity.jdbc.IdentitySchema.verify(ds);
        com.documentplatform.licensing.jdbc.LicensingSchema.verify(ds);
    }

    private static final String DML = "DELETE,INSERT,SELECT,UPDATE";

    private static void assertRights(ThrowawayDatabase on, Map<String, String> expected) throws SQLException {
        Map<String, String> found = new java.util.TreeMap<>();
        try (Connection c = on.connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "select table_name, string_agg(privilege_type, ',' order by privilege_type) from information_schema.role_table_grants "
                        + "where grantee = '" + RUNTIME_USER + "' group by table_name")) {
            while (rs.next()) found.put(rs.getString(1), rs.getString(2));
        }
        assertEquals(new java.util.TreeMap<>(expected), found);
    }

    /** Task 0054: the app's user does its licence work, and none of what 0.7.0 takes away from it (SEC-13, SEC-33). */
    @Test
    @Order(3)
    void theRuntimeUserDoesTheLicenceWorkAndNoneOfWhatIsTakenAway() throws SQLException {
        String at = "timestamptz '2026-11-20T00:00:00Z'";
        try (Connection c = db.connectAs(RUNTIME_USER, RUNTIME_PASSWORD); Statement s = c.createStatement()) {
            // The licence, its version floor (kept by the database itself), the latest time seen.
            s.execute("insert into lic_licence (slot, licence_id, version, tenant_id, starts_on, file, stored_at) values "
                    + "('CURRENT', 'LIC-RT', 7, 'TEN-RT', date '2026-01-01', '{}', " + at + ")");
            assertEquals(7, count(c, "select version from lic_version_floor where slot = 'CURRENT'"));
            s.execute("insert into lic_last_seen (id, last_seen) values (1, " + at + ")");
            s.execute("update lic_last_seen set last_seen = " + at + " + interval '1 day'");
            s.execute("update lic_licence set version = 3");
            assertEquals(7, count(c, "select version from lic_version_floor where slot = 'CURRENT'"), "never lowered");
            s.execute("delete from lic_licence");
            for (String refused : new String[] {
                    "update lic_last_seen set last_seen = " + at + " - interval '1 day'", "delete from lic_last_seen",
                    "insert into lic_version_floor (slot, version) values ('WAITING', 1)", "update lic_version_floor set version = 1",
                    "delete from lic_version_floor", "update lic_clock_reset set reset_id = 'x'", "delete from lic_clock_reset",
                    "update idn_login_connection_history set changed_by = 'x'", "delete from idn_login_connection_history",
                    "update idn_machine_binding_history set changed_by = 'x'", "delete from idn_machine_binding_history",
                    "update lic_schema_version set version = 9", "truncate lic_usage", "select count(*) from lic_schema_history"}) {
                SQLException e = assertThrows(SQLException.class, () -> s.execute(refused), refused);
                assertEquals("42501", e.getSQLState(), refused + ": " + e.getMessage());
            }
            // The app writes these histories itself.
            assertEquals(0, count(c, "select count(*) from idn_login_connection_history"));
            assertEquals(0, count(c, "select count(*) from lic_clock_reset"));
        }
    }

    /**
     * Task 0054: the owner's database today: identity V3 and licensing V1 (platform-core 0.2.0's files), with the rights the
     * 0.2.0 schema step granted. The step takes it to V5 and V2, keeps what is stored, and takes the rights away that are
     * no longer needed.
     */
    @Test
    @Order(5)
    void aDatabaseAtIdentityVersion3AndLicensingVersion1GetsTheLatestAndTheNarrowerRights() throws SQLException {
        try (ThrowawayDatabase v3 = ThrowawayDatabase.create("schemav3")) {
            for (String[] run : new String[][] {{"identity", "idn_schema_history", "3"}, {"licensing", "lic_schema_history", "1"}}) {
                Flyway.configure(SchemaStep.class.getClassLoader()).dataSource(v3.url, v3.user, v3.password)
                        .locations("classpath:db/platform/" + run[0]).table(run[1])
                        .baselineOnMigrate(true).baselineVersion("0").target(run[2]).load().migrate();
            }
            try (Connection c = v3.connect(); Statement s = c.createStatement()) {
                s.execute("grant select, insert, update, delete on all tables in schema public to " + RUNTIME_USER);
                s.execute("insert into idn_customer (tenant_id, name) values ('TEN-OLD', 'Old')");
                s.execute("insert into lic_licence (slot, licence_id, version, tenant_id, starts_on, file, stored_at) values "
                        + "('CURRENT', 'LIC-OLD', 4, 'TEN-OLD', date '2026-01-01', '{}', now())");
                s.execute("insert into idn_grant_history (tenant_id, product_code, slot_id, entity_uuid, issuer_uri, external_group, "
                        + "role_after, change, change_source, changed_by) values ('TEN-OLD', 'LEXAVAULT', 'ENT-OLD', '"
                        + UUID.randomUUID() + "', 'https://login.example', 'G', 'REPORT_VIEWER', 'ADDED', 'APP', 'app')");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
            assertEquals(0, new SchemaStep(print, print).run(new String[] {"apply", "--runtime-user", RUNTIME_USER},
                    v3.env(SchemaStep.ENV_PREFIX)), out.toString(StandardCharsets.UTF_8));
            String text = out.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("identity         2 migration(s) run, at version 5"), text);
            assertTrue(text.contains("licensing        1 migration(s) run, at version 2"), text);
            try (Connection c = v3.connect()) {
                assertEquals(1, count(c, "select count(*) from idn_customer where tenant_id = 'TEN-OLD'"));
                assertEquals(1, count(c, "select count(*) from idn_grant_history where scope = 'ENTITY'"), "older rows are entity rows");
                assertEquals(4, count(c, "select version from lic_version_floor"), "the stored licence's version is the floor");
            }
            try (Connection c = v3.connectAs(RUNTIME_USER, RUNTIME_PASSWORD); Statement s = c.createStatement()) {
                s.execute("select count(*) from lic_version_floor");
                s.execute("insert into idn_platform_grant (tenant_id, product_code, issuer_uri, external_group, role) values "
                        + "('TEN-OLD', 'LEXAVAULT', 'https://login.example', 'Ops', 'PLATFORM_OPERATIONS_ADMIN')");
                for (String refused : new String[] {"delete from lic_last_seen", "delete from lic_clock_reset",
                        "update lic_clock_reset set reset_id = 'x'", "update idn_grant_history set changed_by = 'x'",
                        "select count(*) from idn_schema_history"}) {
                    SQLException e = assertThrows(SQLException.class, () -> s.execute(refused), refused);
                    assertEquals("42501", e.getSQLState(), refused);
                }
            }
            try (Connection c = v3.connect(); Statement s = c.createStatement()) {
                s.execute("revoke all on all tables in schema public from " + RUNTIME_USER);
            }
        }
    }

    /** Task 0054: rights cannot be taken from the tables' owner, so the owner is not a runtime user. */
    @Test
    @Order(6)
    void theOwnerOfTheTablesIsNoRuntimeUser() {
        assertTrue(run(2, "apply", "--runtime-user", db.user).contains("RUNTIME_USER_OWNS_TABLES"));
    }

    /**
     * Task 0037 fix 1: a database whose schema step ran with identity V1 only (the owner's, after the start that
     * failed) gets every later version on the next schema step.
     */
    @Test
    @Order(3)
    void aDatabaseAtIdentityVersion1GetsTheLatestVersion() throws SQLException {
        try (ThrowawayDatabase v1 = ThrowawayDatabase.create("schemav1")) {
            Flyway.configure(SchemaStep.class.getClassLoader()).dataSource(v1.url, v1.user, v1.password)
                    .locations("classpath:db/platform/identity").table("idn_schema_history")
                    .baselineOnMigrate(true).baselineVersion("0").target("1").load().migrate();
            try (Connection c = v1.connect()) {
                assertEquals(1, count(c, "select max(version) from idn_schema_version"));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
            assertEquals(0, new SchemaStep(print, print).run(new String[] {"apply"}, v1.env(SchemaStep.ENV_PREFIX)));
            String text = out.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("identity         4 migration(s) run, at version 5"), text);
            try (Connection c = v1.connect()) {
                assertEquals(5, count(c, "select max(version) from idn_schema_version"));
            }
        }
    }

    /** Task 0041: a database at identity V2 (after 0037 fix 1) gets the later versions only. */
    @Test
    @Order(4)
    void aDatabaseAtIdentityVersion2GetsTheLaterVersions() throws SQLException {
        try (ThrowawayDatabase v2 = ThrowawayDatabase.create("schemav2")) {
            Flyway.configure(SchemaStep.class.getClassLoader()).dataSource(v2.url, v2.user, v2.password)
                    .locations("classpath:db/platform/identity").table("idn_schema_history")
                    .baselineOnMigrate(true).baselineVersion("0").target("2").load().migrate();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
            assertEquals(0, new SchemaStep(print, print).run(new String[] {"apply"}, v2.env(SchemaStep.ENV_PREFIX)));
            String text = out.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("identity         3 migration(s) run, at version 5"), text);
            try (Connection c = v2.connect()) {
                assertEquals(5, count(c, "select max(version) from idn_schema_version"));
                assertEquals(1, count(c, "select count(*) from information_schema.tables where table_name = 'idn_grant_history'"));
            }
        }
    }

    private static int exitOf(String... args) {
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        return new SchemaStep(quiet, quiet).run(args, db.env(SchemaStep.ENV_PREFIX));
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
