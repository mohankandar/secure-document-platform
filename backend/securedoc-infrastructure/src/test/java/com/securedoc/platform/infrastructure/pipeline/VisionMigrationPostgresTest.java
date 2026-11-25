package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import com.securedoc.platform.domain.vision.PageRoute;
import com.securedoc.platform.infrastructure.persistence.adapter.JdbcPageRouteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0064: migration 080: VISION jobs, page routes, vision settings, and their rollback. */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class VisionMigrationPostgresTest {

    @Test
    void oneVisionJobPerPageAndRoutesComeBackAsSaved() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m080")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            JdbcPipelineJobRepository jobs = new JdbcPipelineJobRepository(jdbc);
            TenantId tenant = TenantId.generate();
            PackageId pkg = PackageId.generate();
            TestRows.tenant(jdbc, tenant);
            TestRows.packageRow(jdbc, tenant, pkg);
            DocumentId doc = DocumentId.generate();
            assertTrue(jobs.enqueue(vision(tenant, pkg, doc, 3, 3)));
            assertFalse(jobs.enqueue(vision(tenant, pkg, doc, 3, 3)), "queuing twice adds nothing");
            assertTrue(jobs.enqueue(vision(tenant, pkg, doc, 4, 4)));
            assertTrue(jobs.enqueue(vision(tenant, pkg, DocumentId.generate(), 3, 3)));
            assertTrue(jobs.enqueue(PipelineJob.readyBatch(tenant, PipelineStage.FIND, pkg, doc, 3, 3, 1, Instant.now())));
            assertTrue(jobs.hasStageJobs(tenant, pkg));
            assertTrue(jobs.hasOpenStageJobs());
            assertThrows(DataIntegrityViolationException.class, () -> jobs.enqueue(vision(tenant, pkg, doc, 5, 6)),
                    "a vision job has one page");
            assertEquals(4, jobs.removeStageJobs(tenant, pkg, 1), "a case started again starts with no vision job");

            documentRow(jdbc, tenant, pkg, doc);
            JdbcPageRouteRepository routes = new JdbcPageRouteRepository(jdbc);
            PageRoute fast = new PageRoute(1, PageRoute.Path.FAST, Set.of(), null, List.of());
            PageRoute signed = new PageRoute(2, PageRoute.Path.FAST_SIGNATURE, Set.of(PageRoute.Reason.SIGNATURE_ONLY),
                    0.93, List.of(BoundingBox.of(0.1, 0.2, 0.3, 0.04), BoundingBox.of(0.5, 0.6, 0.1, 0.1)));
            PageRoute hard = new PageRoute(3, PageRoute.Path.VISION,
                    Set.of(PageRoute.Reason.HANDWRITING, PageRoute.Reason.LOW_OCR_CONFIDENCE), 0.0, List.of());
            routes.save(tenant, doc, List.of(hard, fast, signed));
            assertEquals(List.of(fast, signed, hard), routes.findByDocument(tenant, doc));
            PageRoute again = new PageRoute(1, PageRoute.Path.VISION, Set.of(PageRoute.Reason.PICTURE), null, List.of());
            routes.save(tenant, doc, List.of(again));
            assertEquals(List.of(again, signed, hard), routes.findByDocument(tenant, doc), "read again: the new route");
            assertTrue(routes.findByDocument(TenantId.generate(), doc).isEmpty(), "another entity sees nothing");
            jdbc.update("DELETE FROM documents WHERE id = :d", new MapSqlParameterSource("d", doc.value()));
            assertTrue(routes.findByDocument(tenant, doc).isEmpty(), "deleted with its document");
        }
    }

    @Test
    void existingPoliciesGetTheDefaultsAndTheRollbackRestoresTheVersionBefore() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m080r")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            JdbcPipelineJobRepository jobs = new JdbcPipelineJobRepository(jdbc);
            TenantId tenant = TenantId.generate();
            PackageId pkg = PackageId.generate();
            TestRows.tenant(jdbc, tenant);
            TestRows.packageRow(jdbc, tenant, pkg);
            jdbc.update("INSERT INTO tenant_redaction_policies (tenant_id, auto_redact_threshold, review_mode, updated_at)"
                    + " VALUES (:t, 0.8, 'UNCERTAIN_ONLY', now())",
                    new MapSqlParameterSource("t", tenant.value()));
            assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT vision_enabled AND vision_ocr_threshold = 0.85"
                    + " FROM tenant_redaction_policies", new MapSqlParameterSource(), Boolean.class));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                    "UPDATE tenant_redaction_policies SET vision_ocr_threshold = 1.2", new MapSqlParameterSource()));
            DocumentId doc = DocumentId.generate();
            jobs.enqueue(vision(tenant, pkg, doc, 1, 1));
            jobs.enqueue(PipelineJob.ready(tenant, PipelineStage.TEXT, pkg, doc, null, null, 1, Instant.now()));
            try (Connection connection = db.connection()) {
                PostgresTestDatabase.rollback(PostgresTestDatabase.liquibase(connection), connection, 8); // 067, 072, 084, 083, 082, 081, 071, then 080
            }
            assertEquals(List.of("TEXT"), jdbc.queryForList("SELECT stage FROM pipeline_jobs",
                    new MapSqlParameterSource(), String.class));
            // Queuing reads it (task 0073, migration 082).
            jdbc.getJdbcOperations().execute("CREATE TABLE case_priorities (package_id uuid, tenant_id uuid, priority varchar(16), source_key varchar(128))");
            assertThrows(DataIntegrityViolationException.class, () -> jobs.enqueue(vision(tenant, pkg, doc, 2, 2)));
            assertFalse(jobs.enqueue(PipelineJob.ready(tenant, PipelineStage.TEXT, pkg, doc, null, null, 1,
                    Instant.now())), "the identity holds");
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE column_name IN"
                    + " ('vision_enabled', 'vision_ocr_threshold', 'found_by', 'ai_models')",
                    new MapSqlParameterSource(), Integer.class));
        }
    }

    private static PipelineJob vision(TenantId tenant, PackageId pkg, DocumentId doc, int from, int to) {
        return PipelineJob.readyBatch(tenant, PipelineStage.VISION, pkg, doc, from, to, 1, Instant.now());
    }

    private static void documentRow(NamedParameterJdbcTemplate jdbc, TenantId tenant, PackageId pkg, DocumentId doc) {
        jdbc.update("""
                INSERT INTO documents (id, tenant_id, package_id, file_name, mime_type, size_bytes, checksum_algorithm,
                    checksum_value, document_type, status, created_at, updated_at)
                VALUES (:id, :t, :p, 'a.pdf', 'application/pdf', 1, 'SHA-256', :c, 'PDF', 'DETECTING', now(), now())""",
                new MapSqlParameterSource("id", doc.value()).addValue("t", tenant.value()).addValue("p", pkg.value())
                        .addValue("c", "a".repeat(64)));
    }
}
