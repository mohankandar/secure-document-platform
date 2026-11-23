package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineJobStatus;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0048: migration 049 lets one case run hold one Text, Find and Redact job per document (and later per page
 * range), leaves the export jobs' identity as it was, and the expired-claim sweep can be limited to some stages, so the
 * export workers and the stage workers each release only their own jobs.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class StageJobsMigrationPostgresTest {

    @Test
    void oneStageJobPerDocumentAndExportJobsKeepTheirIdentity() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m049id")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            JdbcPipelineJobRepository jobs = new JdbcPipelineJobRepository(jdbc);
            TenantId tenant = TenantId.generate();
            PackageId pkg = PackageId.generate();
            TestRows.tenant(jdbc, tenant);
            TestRows.packageRow(jdbc, tenant, pkg);
            DocumentId a = DocumentId.generate();
            DocumentId b = DocumentId.generate();

            assertTrue(jobs.enqueue(job(tenant, PipelineStage.TEXT, pkg, a)));
            assertTrue(jobs.enqueue(job(tenant, PipelineStage.TEXT, pkg, b)), "another document: its own job");
            assertFalse(jobs.enqueue(job(tenant, PipelineStage.TEXT, pkg, a)), "queuing twice adds nothing");
            assertTrue(jobs.enqueue(job(tenant, PipelineStage.FIND, pkg, a)));
            assertTrue(jobs.hasStageJobs(tenant, pkg));

            // Export jobs: the document is not part of their identity (as before 049).
            IntegrationConnectionId destination = IntegrationConnectionId.generate();
            TestRows.connection(jdbc, tenant, destination);
            assertTrue(jobs.enqueue(PipelineJob.ready(tenant, PipelineStage.EXPORT, pkg, null, destination, null, 1,
                    Instant.now())));
            assertFalse(jobs.enqueue(PipelineJob.ready(tenant, PipelineStage.EXPORT, pkg, a, destination, null, 1,
                    Instant.now())), "one EXPORT job per case run and destination, whatever the document");
            UUID item = UUID.randomUUID();
            assertTrue(jobs.enqueue(PipelineJob.ready(tenant, PipelineStage.SPLIT_EXPORT, pkg, a, destination, item, 1,
                    Instant.now())));
            assertFalse(jobs.enqueue(PipelineJob.ready(tenant, PipelineStage.SPLIT_EXPORT, pkg, b, destination, item, 1,
                    Instant.now())), "one SPLIT_EXPORT job per item and destination, as before");

            PackageId other = PackageId.generate();
            TestRows.packageRow(jdbc, tenant, other);
            assertFalse(jobs.hasStageJobs(tenant, other));

            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                    INSERT INTO pipeline_jobs (id, tenant_id, stage, package_id, export_cycle, status, next_attempt_at,
                        created_at, updated_at)
                    VALUES (:id, :t, 'TEXT', :p, 1, 'READY', now(), now(), now())""",
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("t", tenant.value())
                            .addValue("p", pkg.value())), "a stage job always names its document");
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                    INSERT INTO pipeline_jobs (id, tenant_id, stage, package_id, document_id, export_cycle, status,
                        next_attempt_at, created_at, updated_at, page_from, page_to)
                    VALUES (:id, :t, 'TEXT', :p, :d, 1, 'READY', now(), now(), now(), 5, 2)""",
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("t", tenant.value())
                            .addValue("p", pkg.value()).addValue("d", UUID.randomUUID())), "a page range runs forward");
            // A page range (part 2): the same document's pages 21-40 is a job of its own.
            assertEquals(1, jdbc.update("""
                    INSERT INTO pipeline_jobs (id, tenant_id, stage, package_id, document_id, export_cycle, status,
                        next_attempt_at, created_at, updated_at, page_from, page_to)
                    VALUES (:id, :t, 'TEXT', :p, :d, 1, 'READY', now(), now(), now(), 21, 40)""",
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("t", tenant.value())
                            .addValue("p", pkg.value()).addValue("d", a.value())));
        }
    }

    @Test
    void eachSweepReleasesOnlyItsOwnStagesWhenTheirClaimsRunOut() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m049sweep")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            JdbcPipelineJobRepository jobs = new JdbcPipelineJobRepository(jdbc);
            TenantId tenant = TenantId.generate();
            PackageId pkg = PackageId.generate();
            TestRows.tenant(jdbc, tenant);
            TestRows.packageRow(jdbc, tenant, pkg);
            IntegrationConnectionId destination = IntegrationConnectionId.generate();
            TestRows.connection(jdbc, tenant, destination);
            jobs.enqueue(job(tenant, PipelineStage.TEXT, pkg, DocumentId.generate()));
            jobs.enqueue(PipelineJob.ready(tenant, PipelineStage.EXPORT, pkg, null, destination, null, 1, Instant.now()));
            PipelineJob text = jobs.claimNext(EnumSet.of(PipelineStage.TEXT), "a", Duration.ofMinutes(1), 1).orElseThrow();
            PipelineJob export = jobs.claimNext(EnumSet.of(PipelineStage.EXPORT), "a", Duration.ofMinutes(1), 4)
                    .orElseThrow();
            jdbc.update("UPDATE pipeline_jobs SET claim_until = clock_timestamp() - interval '1 second'",
                    new MapSqlParameterSource());

            List<PipelineJob> stage = jobs.releaseExpiredClaims(
                    EnumSet.of(PipelineStage.TEXT, PipelineStage.FIND, PipelineStage.REDACT), 3, "LV-PRC-008", 10);
            assertEquals(List.of(text.id()), stage.stream().map(PipelineJob::id).toList());
            assertEquals(PipelineJobStatus.RUNNING, jobs.findByTenantIdAndId(tenant, export.id()).orElseThrow().status(),
                    "the stage sweep leaves export jobs to the export sweep");
            List<PipelineJob> exportSweep = jobs.releaseExpiredClaims(
                    EnumSet.of(PipelineStage.EXPORT, PipelineStage.SPLIT_EXPORT), 3, "LV-PRC-008", 10);
            assertEquals(List.of(export.id()), exportSweep.stream().map(PipelineJob::id).toList());
            assertEquals(PipelineJobStatus.READY, jobs.findByTenantIdAndId(tenant, text.id()).orElseThrow().status());
        }
    }

    @Test
    void rollingBack049RemovesTheStageJobsAndRestoresTheOldIdentity() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m049rb")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            JdbcPipelineJobRepository jobs = new JdbcPipelineJobRepository(jdbc);
            TenantId tenant = TenantId.generate();
            PackageId pkg = PackageId.generate();
            TestRows.tenant(jdbc, tenant);
            TestRows.packageRow(jdbc, tenant, pkg);
            IntegrationConnectionId destination = IntegrationConnectionId.generate();
            TestRows.connection(jdbc, tenant, destination);
            jobs.enqueue(job(tenant, PipelineStage.TEXT, pkg, DocumentId.generate()));
            jobs.enqueue(job(tenant, PipelineStage.TEXT, pkg, DocumentId.generate()));
            jobs.enqueue(PipelineJob.ready(tenant, PipelineStage.EXPORT, pkg, null, destination, null, 1, Instant.now()));

            try (Connection connection = db.connection()) {
                PostgresTestDatabase.liquibase(connection).rollback(1, (String) null);
            }
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pipeline_jobs", new MapSqlParameterSource(),
                    Integer.class), "the export job stays; the stage jobs are removed");
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name ="
                    + " 'pipeline_jobs' AND column_name IN ('page_from', 'page_to')", new MapSqlParameterSource(),
                    Integer.class));
            assertFalse(jobs.enqueue(PipelineJob.ready(tenant, PipelineStage.EXPORT, pkg, null, destination, null, 1,
                    Instant.now())), "the old identity holds");
        }
    }

    private static PipelineJob job(TenantId tenant, PipelineStage stage, PackageId pkg, DocumentId document) {
        return PipelineJob.ready(tenant, stage, pkg, document, null, null, 1, Instant.now());
    }
}
