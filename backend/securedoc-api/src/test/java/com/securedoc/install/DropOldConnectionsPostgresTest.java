package com.securedoc.install;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0058: migration 062 drops the old connection table only when nothing needed is left in it. Throwaway server only. */
@EnabledIfEnvironmentVariable(named = ThrowawayDatabase.URL_VARIABLE, matches = "jdbc:postgresql:.*")
class DropOldConnectionsPostgresTest {

    private ThrowawayDatabase db;
    private IdnRows rows;

    @BeforeEach
    void create() throws SQLException {
        db = ThrowawayDatabase.create("dropold");
        assertEquals(0, schemaStep());
        rows = new IdnRows(db);
        rows.customer("TEN-DROP-0001");
        // As a database of the release before: the table is there and 062 has not run.
        rows.exec(OldConnectionTable.CREATE);
        rows.exec("delete from databasechangelog where id = '062-01-drop-old-identity-connections'");
    }

    @AfterEach
    void drop() throws SQLException {
        if (db != null) db.close();
    }

    private String output = "";

    private int schemaStep() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        int exit = new SchemaStep(print, print).run(new String[] {"apply"}, db.env(SchemaStep.ENV_PREFIX));
        output = out.toString(StandardCharsets.UTF_8);
        return exit;
    }

    private boolean exists(String table) {
        return Boolean.TRUE.equals(new JdbcTemplate(rows.ds).queryForObject("select to_regclass(?) is not null", Boolean.class, table));
    }

    @Test
    void anEntitysActiveConnectionThatWasNotCarriedOverStopsTheStepAndNothingIsDropped() throws SQLException {
        UUID entity = rows.slot("TEN-DROP-0001", "ENT-DROP-A", "ACTIVE");
        rows.lexaTenant(entity, "ACTIVE");
        rows.exec(OldConnectionTable.INSERT_ACTIVE, UUID.randomUUID(), entity);
        assertNotEquals(0, schemaStep());
        assertTrue(exists("enterprise_identity_connections"), "still there");
        assertEquals(1, new JdbcTemplate(rows.ds).queryForObject("select count(*) from enterprise_identity_connections", Integer.class));

        rows.connection("TEN-DROP-0001", "new-okta", URI.create("https://login.new.example"), "api://new");
        assertEquals(0, schemaStep(), output);
        assertTrue(!exists("enterprise_identity_connections"));
        assertTrue(exists("enterprise_identity_connection_history"), "the record of old changes stays");
        assertTrue(exists("tenant_oidc_group_grants"));
    }

    @Test
    void aRowOfAnEntityThatIsNoSlotNeverAdmittedAnyoneSinceAndGoes() throws SQLException {
        UUID legacy = UUID.randomUUID();
        rows.lexaTenant(legacy, "ACTIVE");
        rows.exec(OldConnectionTable.INSERT_ACTIVE, UUID.randomUUID(), legacy);
        assertEquals(0, schemaStep(), output);
        assertTrue(!exists("enterprise_identity_connections"));
        assertEquals(0, schemaStep(), "a second run changes nothing");
    }
}
