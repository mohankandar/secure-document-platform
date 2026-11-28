package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.identity.TenantId;
import liquibase.Contexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0104: migration 093 makes every existing case its own batch 1 and each of its documents version 1 of its own
 * lineage, with the file's fingerprint kept. Run as on an existing database: the data change set is forgotten, the rows
 * are written, the changelog runs again.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class CaseBatchesMigrationPostgresTest {

    @Test
    void anExistingCaseIsBatchOneAndItsDocumentsAreVersionOne() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m093")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId tenant = TenantId.generate();
            TestRows.tenant(jdbc, tenant);
            UUID pkg = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO document_packages (id, tenant_id, package_type, status, created_by, created_at, updated_at)
                    VALUES (:id, :tenant, 'MANUAL_UPLOAD', 'COMPLETED', :actor, now(), now())
                    """, new MapSqlParameterSource("id", pkg).addValue("tenant", tenant.value()).addValue("actor", UUID.randomUUID()));
            UUID doc = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO documents (id, tenant_id, package_id, document_type, status, file_name, mime_type, size_bytes,
                                           checksum_algorithm, checksum_value, page_count, created_at, updated_at, version)
                    VALUES (:id, :tenant, :pkg, 'OTHER', 'PROCESSED', 'a.pdf', 'application/pdf', 1234, 'SHA-256', :sum, 2, now(), now(), 0)
                    """, new MapSqlParameterSource("id", doc).addValue("tenant", tenant.value()).addValue("pkg", pkg)
                    .addValue("sum", "AB".repeat(32)));

            jdbc.getJdbcOperations().update("DELETE FROM databasechangelog WHERE id = '093-04-existing-cases-are-batch-one'");
            jdbc.getJdbcOperations().update("DELETE FROM case_batch_files");
            jdbc.getJdbcOperations().update("DELETE FROM case_batches");
            try (Connection connection = db.connection()) {
                PostgresTestDatabase.liquibase(connection).update(new Contexts());
            }

            Map<String, Object> batch = jdbc.queryForMap("SELECT * FROM case_batches WHERE package_id = :pkg",
                    new MapSqlParameterSource("pkg", pkg));
            assertEquals(1, batch.get("batch_number"));
            Map<String, Object> d = jdbc.queryForMap("SELECT batch_id, lineage_id, version_number FROM documents WHERE id = :id",
                    new MapSqlParameterSource("id", doc));
            assertEquals(batch.get("id"), d.get("batch_id"));
            assertEquals(doc, d.get("lineage_id"));
            assertEquals(1, d.get("version_number"));
            Map<String, Object> file = jdbc.queryForMap("SELECT * FROM case_batch_files WHERE document_id = :id",
                    new MapSqlParameterSource("id", doc));
            assertEquals("ab".repeat(32), file.get("sha256"), "the fingerprint, lower case");
            assertEquals("NEW_DOCUMENT", file.get("outcome"));
            assertNull(file.get("name_hash"), "no name kept");
        }
    }
}
