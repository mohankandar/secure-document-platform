package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0087: migration 067 keeps every existing row, and its rollback does not forget a delivery that has begun. */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class DestinationProfilesMigrationPostgresTest {

    @Test
    void existingRowsAreKeptAndTheRollbackIsRefusedWhileADeliveryIsHalfDone() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m067")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            try (Connection connection = db.connection()) {
                PostgresTestDatabase.rollback(PostgresTestDatabase.liquibase(connection), connection,
                        PostgresTestDatabase.changeSetsFrom(connection, "067-")); // a 0094 database (090 too, when it ran)
            }
            TenantId tenant = TenantId.generate();
            PackageId pkg = PackageId.generate();
            IntegrationConnectionId old = IntegrationConnectionId.generate();
            TestRows.tenant(jdbc, tenant);
            TestRows.connection(jdbc, tenant, old);
            TestRows.packageRow(jdbc, tenant, pkg);
            UUID submission = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO delivery_submissions (id, tenant_id, package_id, export_cycle, source_submission_id,
                        destination_type, status, attempt_count, destination_submission_reference, created_at, updated_at)
                    VALUES (:id, :t, :p, 1, 'sub-1', 'document-store', 'PARTIALLY_ACKNOWLEDGED', 1, 'remote-7', now(), now())
                    """, new MapSqlParameterSource("id", submission).addValue("t", tenant.value()).addValue("p", pkg.value()));

            try (Connection connection = db.connection()) {
                PostgresTestDatabase.liquibase(connection).update(new liquibase.Contexts());
            }
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM integration_connections WHERE id = :id AND enabled"
                    + " AND destination_settings IS NULL", new MapSqlParameterSource("id", old.value()), Integer.class),
                    "kept and switched on; refused at delivery until IT chooses its profile");
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM delivery_submissions WHERE id = :id"
                    + " AND destination_submission_reference = 'remote-7' AND destination_progress IS NULL",
                    new MapSqlParameterSource("id", submission), Integer.class));

            jdbc.update("UPDATE delivery_submissions SET destination_progress = '{}' WHERE id = :id", new MapSqlParameterSource("id", submission));
            Exception refused = assertThrows(Exception.class, () -> {
                try (Connection connection = db.connection()) {
                    PostgresTestDatabase.rollback(PostgresTestDatabase.liquibase(connection), connection,
                            PostgresTestDatabase.changeSetsFrom(connection, "067-"));
                }
            });
            assertTrue(String.valueOf(refused).contains("DESTINATION_PROGRESS_OPEN")
                    || String.valueOf(refused.getCause()).contains("DESTINATION_PROGRESS_OPEN"), String.valueOf(refused));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE column_name = 'destination_progress'",
                    new MapSqlParameterSource(), Integer.class));
            jdbc.update("UPDATE delivery_submissions SET status = 'DELIVERED' WHERE id = :id", new MapSqlParameterSource("id", submission));
            try (Connection connection = db.connection()) {
                PostgresTestDatabase.rollback(PostgresTestDatabase.liquibase(connection), connection,
                            PostgresTestDatabase.changeSetsFrom(connection, "067-"));
            }
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE column_name IN"
                    + " ('destination_progress', 'destination_settings')", new MapSqlParameterSource(), Integer.class));
        }
    }
}
