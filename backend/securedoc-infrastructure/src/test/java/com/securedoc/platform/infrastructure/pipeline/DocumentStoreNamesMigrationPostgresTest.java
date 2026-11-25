package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import liquibase.Contexts;
import liquibase.Liquibase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Task 0045: migration 046 gives the stored values that named another product the generic "document store" name.
 * Rows written before it (the old values) come out with the new ones; everything else in those rows is unchanged; the
 * "one enabled document store per entity" index is still there under its new name. This test and the migration are
 * the only places outside docs/ that spell the old values (the product-name guard lists both).
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class DocumentStoreNamesMigrationPostgresTest {
    private static final String OLD_PURPOSE = "SDV_DELIVERY";
    private static final String OLD_ACK = "SDV_SUBMISSION_ACK";
    private static final String OLD_FAILURE = "SDV_CONNECTION_TEST_FAILED";
    private static final String OLD_TYPE = "sdv";

    @Test
    void rowsWrittenBefore046GetTheNewNamesAndKeepEverythingElse() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.empty("m046")) {
            try (Connection connection = db.connection()) {
                Liquibase liquibase = PostgresTestDatabase.liquibase(connection);
                liquibase.update(new Contexts());
                PostgresTestDatabase.rollback(liquibase, connection, 15); // back to 045 (082, 081, 071, 080, 070, 064, 063, 062, 061, 060, 050, 049, 048, 047, then 046): the old values and the old index name
            }
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            assertEquals(1, indexCount(jdbc, "uk_integration_connection_enabled_sdv"), "045: old index name");

            TenantId tenant = TenantId.generate();
            TestRows.tenant(jdbc, tenant);
            PackageId pkg = PackageId.generate();
            TestRows.packageRow(jdbc, tenant, pkg);
            UUID store = UUID.randomUUID();
            UUID derived = UUID.randomUUID();
            insertConnection(jdbc, store, tenant, "Main store", OLD_PURPOSE, OLD_ACK, OLD_FAILURE);
            insertConnection(jdbc, derived, tenant, "Split", "DERIVED_DOCUMENT_DELIVERY", "SOURCE_PICKUP_ACK", "DELIVERY_IO");
            UUID exportRecord = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO package_export_records (id, tenant_id, package_id, cycle_number, destination_type,
                        exported_at, correlation_id)
                    VALUES (:id, :t, :p, 1, :type, now(), :c)
                    """, new MapSqlParameterSource("id", exportRecord).addValue("t", tenant.value())
                    .addValue("p", pkg.value()).addValue("type", OLD_TYPE).addValue("c", UUID.randomUUID()));
            UUID localRecord = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO package_export_records (id, tenant_id, package_id, cycle_number, destination_type,
                        exported_at, correlation_id)
                    VALUES (:id, :t, :p, 2, 'local-folder', now(), :c)
                    """, new MapSqlParameterSource("id", localRecord).addValue("t", tenant.value())
                    .addValue("p", pkg.value()).addValue("c", UUID.randomUUID()));
            UUID submission = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO delivery_submissions (id, tenant_id, package_id, export_cycle, source_submission_id,
                        destination_type, status, attempt_count, destination_submission_reference, created_at, updated_at)
                    VALUES (:id, :t, :p, 1, 'src-1', :type, 'DELIVERED', 1, 'ref-1', now(), now())
                    """, new MapSqlParameterSource("id", submission).addValue("t", tenant.value())
                    .addValue("p", pkg.value()).addValue("type", OLD_TYPE));

            try (Connection connection = db.connection()) {
                PostgresTestDatabase.liquibase(connection).update(new Contexts());
            }

            assertEquals(List.of("Main store|DOCUMENT_STORE_DELIVERY|SUBMISSION_ACK|DOCUMENT_STORE_CONNECTION_TEST_FAILED|true",
                            "Split|DERIVED_DOCUMENT_DELIVERY|SOURCE_PICKUP_ACK|DELIVERY_IO|true"),
                    jdbc.query("SELECT name || '|' || purpose || '|' || acknowledgement_mode || '|' || last_failure_category"
                                    + " || '|' || enabled FROM integration_connections ORDER BY name",
                            new MapSqlParameterSource(), (rs, n) -> rs.getString(1)));
            assertEquals("document-store", typeOf(jdbc, "package_export_records", exportRecord));
            assertEquals("local-folder", typeOf(jdbc, "package_export_records", localRecord), "other types unchanged");
            assertEquals("document-store", typeOf(jdbc, "delivery_submissions", submission));
            assertEquals("ref-1", jdbc.queryForObject("SELECT destination_submission_reference FROM delivery_submissions"
                    + " WHERE id = :id", new MapSqlParameterSource("id", submission), String.class), "rest of the row kept");
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM integration_connections WHERE purpose = :o"
                    + " OR acknowledgement_mode = :a", new MapSqlParameterSource("o", OLD_PURPOSE).addValue("a", OLD_ACK),
                    Integer.class), "no old value left");

            assertEquals(0, indexCount(jdbc, "uk_integration_connection_enabled_sdv"), "old index gone");
            assertEquals(1, indexCount(jdbc, "uk_integration_connection_enabled_document_store"), "new index there");
            // Still one enabled document store per entity.
            assertThrows(DuplicateKeyException.class, () -> insertConnection(jdbc, UUID.randomUUID(), tenant, "Second store",
                    "DOCUMENT_STORE_DELIVERY", "SUBMISSION_ACK", null));
        }
    }

    @Test
    void rollingBack046GivesTheOldValuesBack() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m046rb")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId tenant = TenantId.generate();
            TestRows.tenant(jdbc, tenant);
            insertConnection(jdbc, UUID.randomUUID(), tenant, "Main store", "DOCUMENT_STORE_DELIVERY", "SUBMISSION_ACK", null);
            try (Connection connection = db.connection()) {
                PostgresTestDatabase.rollback(PostgresTestDatabase.liquibase(connection), connection, 15); // 082, 081, 071, 080, 070, 064, 063, 062, 061, 060, 050, 049, 048, 047, then 046
            }
            assertEquals(OLD_PURPOSE + "|" + OLD_ACK, jdbc.queryForObject("SELECT purpose || '|' || acknowledgement_mode"
                    + " FROM integration_connections", new MapSqlParameterSource(), String.class));
            assertEquals(1, indexCount(jdbc, "uk_integration_connection_enabled_sdv"));
            assertEquals(0, indexCount(jdbc, "uk_integration_connection_enabled_document_store"));
        }
    }

    private static void insertConnection(NamedParameterJdbcTemplate jdbc, UUID id, TenantId tenant, String name,
                                         String purpose, String ack, String failure) {
        jdbc.update("""
                INSERT INTO integration_connections
                    (id, tenant_id, name, purpose, direction, provider_type, authentication_type, timeout_seconds,
                     retry_max_attempts, acknowledgement_mode, enabled, configuration_version, health_status,
                     last_failure_category, created_at, updated_at)
                VALUES (:id, :tenant, :name, :purpose, 'OUTBOUND', 'HTTP_API', 'NONE', 5, 0, :ack, true, 1, 'UNKNOWN',
                        :failure, now(), now())
                """, new MapSqlParameterSource("id", id).addValue("tenant", tenant.value()).addValue("name", name)
                .addValue("purpose", purpose).addValue("ack", ack).addValue("failure", failure));
    }

    private static String typeOf(NamedParameterJdbcTemplate jdbc, String table, UUID id) {
        return jdbc.queryForObject("SELECT destination_type FROM " + table + " WHERE id = :id",
                new MapSqlParameterSource("id", id), String.class);
    }

    private static int indexCount(NamedParameterJdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = :n",
                new MapSqlParameterSource("n", name), Integer.class);
    }
}
