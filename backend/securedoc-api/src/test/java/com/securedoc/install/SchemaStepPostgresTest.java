package com.securedoc.install;

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
        assertTrue(first.contains("identity         1 migration(s) run, at version 1"), first);
        assertTrue(first.contains("licensing        1 migration(s) run, at version 1"), first);
        try (Connection c = db.connect()) {
            assertEquals(1, count(c, "select count(*) from idn_schema_history where version = '1' and success"));
            assertEquals(1, count(c, "select count(*) from lic_schema_history where version = '1' and success"));
            assertEquals(1, count(c, "select max(version) from idn_schema_version"));
            assertEquals(1, count(c, "select max(version) from lic_schema_version"));
            assertTrue(count(c, "select count(*) from databasechangelog") > 90, "the app's changelog ran");
            assertEquals(1, count(c, "select count(*) from information_schema.tables where table_name = 'tenants'"));
        }
        String second = run(0, "apply");
        assertTrue(second.contains("identity         0 migration(s) run, at version 1"), second);
        assertTrue(second.contains("licensing        0 migration(s) run, at version 1"), second);
        String status = run(0, "status");
        assertTrue(status.contains("identity         version 1") && status.contains("licensing        version 1"), status);
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
            assertThrows(SQLException.class, () -> s.execute("update idn_schema_version set version = 2"));
            assertThrows(SQLException.class, () -> s.execute("delete from idn_schema_history"));
            assertThrows(SQLException.class, () -> s.execute("create table lv_not_allowed (id int)"));
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
