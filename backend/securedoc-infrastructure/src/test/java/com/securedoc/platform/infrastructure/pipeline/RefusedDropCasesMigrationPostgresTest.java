package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.identity.TenantId;
import liquibase.Contexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Task 0102: migration 086 closes the cases refused folder drops left open (owner test U3, contract 779001126006) and no
 * other case. Run as on an existing database: the change set is forgotten, the rows are written, the changelog runs again.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class RefusedDropCasesMigrationPostgresTest {

    @Test
    void onlyAnOpenFolderCaseWhoseDropWasRefusedIsClosedOutOfTheDashboard() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m086")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId tenant = TenantId.generate();
            TestRows.tenant(jdbc, tenant);
            Instant earlier = Instant.parse("2026-11-25T10:00:00Z");
            Instant later = Instant.parse("2026-11-25T10:05:00Z");

            UUID stuck = pkg(jdbc, tenant, "FOLDER_INTAKE", "CREATED", "installed/aaaaaaaaaaaa");
            observation(jdbc, tenant, "installed/aaaaaaaaaaaa", "REJECTED", earlier);

            UUID retaken = pkg(jdbc, tenant, "FOLDER_INTAKE", "CREATED", "installed/bbbbbbbbbbbb");
            observation(jdbc, tenant, "installed/bbbbbbbbbbbb", "REJECTED", earlier);
            observation(jdbc, tenant, "installed/bbbbbbbbbbbb", "WAITING_FOR_TRIGGER", later);

            UUID beingTaken = pkg(jdbc, tenant, "FOLDER_INTAKE", "CREATED", "installed/cccccccccccc");

            UUID underWay = pkg(jdbc, tenant, "FOLDER_INTAKE", "SUBMITTED", "installed/dddddddddddd");
            observation(jdbc, tenant, "installed/dddddddddddd", "REJECTED", earlier);

            UUID manual = pkg(jdbc, tenant, "MANUAL_UPLOAD", "CREATED", "installed/eeeeeeeeeeee");
            observation(jdbc, tenant, "installed/eeeeeeeeeeee", "REJECTED", earlier);

            jdbc.getJdbcOperations().update("DELETE FROM databasechangelog WHERE id = '086-01-close-refused-drop-cases'");
            try (Connection connection = db.connection()) {
                PostgresTestDatabase.liquibase(connection).update(new Contexts());
            }

            assertEquals("CANCELLED", status(jdbc, stuck));
            assertNotNull(hiddenAt(jdbc, stuck), "out of the dashboard");
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM intake_observations WHERE source_reference = "
                    + "'installed/aaaaaaaaaaaa' AND status = 'REJECTED'", new MapSqlParameterSource(), Integer.class),
                    "the intake problem stays for reports");
            for (UUID untouched : new UUID[]{retaken, beingTaken, underWay, manual}) {
                assertNull(hiddenAt(jdbc, untouched), untouched.toString());
            }
            assertEquals("CREATED", status(jdbc, retaken), "its drop came back after the refusal");
            assertEquals("CREATED", status(jdbc, beingTaken), "no refusal: a claimed drop still being taken in");
            assertEquals("SUBMITTED", status(jdbc, underWay));
            assertEquals("CREATED", status(jdbc, manual));
        }
    }

    private static UUID pkg(NamedParameterJdbcTemplate jdbc, TenantId tenant, String type, String status, String source) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO document_packages (id, tenant_id, package_type, status, source_reference, created_by, created_at, updated_at)
                VALUES (:id, :tenant, :type, :status, :source, :actor, now(), now())
                """, new MapSqlParameterSource("id", id).addValue("tenant", tenant.value()).addValue("type", type)
                .addValue("status", status).addValue("source", source).addValue("actor", UUID.randomUUID()));
        return id;
    }

    private static void observation(NamedParameterJdbcTemplate jdbc, TenantId tenant, String source, String status, Instant at) {
        jdbc.update("""
                INSERT INTO intake_observations (id, tenant_id, source_reference, status, first_seen_at, visible_after, updated_at)
                VALUES (:id, :tenant, :source, :status, :at, :at, :at)
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenant", tenant.value())
                .addValue("source", source).addValue("status", status).addValue("at", Timestamp.from(at)));
    }

    private static String status(NamedParameterJdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("SELECT status FROM document_packages WHERE id = :id", new MapSqlParameterSource("id", id),
                String.class);
    }

    private static Timestamp hiddenAt(NamedParameterJdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("SELECT dashboard_hidden_at FROM document_packages WHERE id = :id",
                new MapSqlParameterSource("id", id), Timestamp.class);
    }
}
