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
                PostgresTestDatabase.rollback(PostgresTestDatabase.liquibase(connection), connection, 10); // 082, 081, 071, 080, 070, 062, 061, 060, 050, then 049
            }
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pipeline_jobs", new MapSqlParameterSource(),
                    Integer.class), "the export job stays; the stage jobs are removed");
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name ="
                    + " 'pipeline_jobs' AND column_name IN ('page_from', 'page_to')", new MapSqlParameterSource(),
                    Integer.class));
            // Task 0049: plain SQL, because the job store now writes the page range this rollback removed.
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                    INSERT INTO pipeline_jobs (id, tenant_id, stage, package_id, destination_id, export_cycle, status,
                        next_attempt_at, created_at, updated_at)
                    VALUES (:id, :t, 'EXPORT', :p, :d, 1, 'READY', now(), now(), now())""",
                    new MapSqlParameterSource("id", UUID.randomUUID()).addValue("t", tenant.value())
                            .addValue("p", pkg.value()).addValue("d", destination.value())), "the old identity holds");
        }
    }

    /** Task 0049: migration 050, the policy's page batches; the job keeps and returns its page range. */
    @Test
    void migration050GivesPoliciesTheDefaultsAndRefusesImpossibleBatches() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m050")) {
            db.backTo050();
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId tenant = TenantId.generate();
            TestRows.tenant(jdbc, tenant);
            MapSqlParameterSource t = new MapSqlParameterSource("t", tenant.value());
            jdbc.update("INSERT INTO tenant_redaction_policies (tenant_id, auto_redact_threshold, review_mode, ssn_enabled,"
                    + " mask_ssn_last_four, date_of_birth_enabled, credit_card_number_enabled, credit_score_enabled,"
                    + " updated_at) VALUES (:t, 0.8, 'UNCERTAIN_ONLY', true, true, true, true, true, now())", t);
            assertEquals("20/2", jdbc.queryForObject("SELECT page_batch_size || '/' || page_batch_overlap FROM"
                    + " tenant_redaction_policies WHERE tenant_id = :t", t, String.class));
            for (String bad : List.of("page_batch_size = 0", "page_batch_size = 501", "page_batch_overlap = -1",
                    "page_batch_overlap = 20", "page_batch_overlap = 21, page_batch_size = 50")) {
                assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                        "UPDATE tenant_redaction_policies SET " + bad + " WHERE tenant_id = :t", t), bad);
            }
            // Queuing reads it (task 0073, migration 082).
            jdbc.getJdbcOperations().execute("CREATE TABLE case_priorities (package_id uuid, priority varchar(16))");
            JdbcPipelineJobRepository jobs = new JdbcPipelineJobRepository(jdbc);
            PackageId pkg = PackageId.generate();
            TestRows.packageRow(jdbc, tenant, pkg);
            DocumentId document = DocumentId.generate();
            PipelineJob batch = PipelineJob.readyBatch(tenant, PipelineStage.TEXT, pkg, document, 21, 40, 1,
                    Instant.now());
            assertTrue(jobs.enqueue(batch));
            assertTrue(jobs.enqueue(PipelineJob.readyBatch(tenant, PipelineStage.TEXT, pkg, document, 41, 60, 1,
                    Instant.now())));
            PipelineJob stored = jobs.findByTenantIdAndId(tenant, batch.id()).orElseThrow();
            assertEquals(21, stored.pageFrom());
            assertEquals(40, stored.pageTo());
            PipelineJob claimed = jobs.claimNext(EnumSet.of(PipelineStage.TEXT), "a", Duration.ofMinutes(1), 1)
                    .orElseThrow();
            assertEquals(21, claimed.pageFrom());
            assertEquals(2, jobs.removeStageJobs(tenant, pkg, 1));
            assertFalse(jobs.claimIsCurrent(claimed.id(), claimed.claimToken()), "a removed job saves nothing");

            try (Connection connection = db.connection()) {
                PostgresTestDatabase.rollback(PostgresTestDatabase.liquibase(connection), connection, 1); // 050
            }
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name ="
                    + " 'tenant_redaction_policies' AND column_name LIKE 'page_batch%'", new MapSqlParameterSource(),
                    Integer.class));
        }
    }

    /** Task 0049, step 2: entity A queues 200 documents, then B queues 5: they take turns. */
    @Test
    void entitiesTakeTurnsWithinAStageAndPriorityOldestFirstWithinEach() throws Exception {
        for (boolean export : new boolean[]{false, true}) {
            try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m050fair")) {
                NamedParameterJdbcTemplate jdbc = db.jdbc();
                JdbcPipelineJobRepository jobs = new JdbcPipelineJobRepository(jdbc);
                TenantId a = TenantId.generate();
                TenantId b = TenantId.generate();
                List<UUID> ofA = queue(jdbc, jobs, a, 200, export);
                List<UUID> ofB = queue(jdbc, jobs, b, 5, export);
                PipelineStage stage = export ? PipelineStage.EXPORT : PipelineStage.TEXT;

                List<PipelineJob> claimed = new java.util.ArrayList<>();
                for (int i = 0; i < 205; i++) {
                    claimed.add(jobs.claimNext(EnumSet.of(stage), "a", Duration.ofMinutes(5), 1000).orElseThrow());
                }
                List<UUID> first10 = claimed.stream().limit(10).map(PipelineJob::id).toList();
                assertTrue(first10.containsAll(ofB), "B's 5 documents start within the next 10 claims, not after A's 200");
                assertEquals(ofA, claimed.stream().filter(j -> j.tenantId().equals(a)).map(PipelineJob::id).toList(),
                        "oldest first within A");
                assertEquals(ofB, claimed.stream().filter(j -> j.tenantId().equals(b)).map(PipelineJob::id).toList(),
                        "oldest first within B");
            }
        }
    }

    private static List<UUID> queue(NamedParameterJdbcTemplate jdbc, JdbcPipelineJobRepository jobs, TenantId tenant,
                                    int count, boolean export) {
        TestRows.tenant(jdbc, tenant);
        IntegrationConnectionId destination = IntegrationConnectionId.generate();
        if (export) TestRows.connection(jdbc, tenant, destination);
        List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            PackageId pkg = PackageId.generate();
            TestRows.packageRow(jdbc, tenant, pkg);
            PipelineJob job = export
                    ? PipelineJob.ready(tenant, PipelineStage.EXPORT, pkg, null, destination, null, 1, Instant.now())
                    : job(tenant, PipelineStage.TEXT, pkg, DocumentId.generate());
            assertTrue(jobs.enqueue(job));
            ids.add(job.id());
        }
        return ids;
    }

    /** Priority comes before turns: an Urgent job of the entity just served still goes first. */
    @Test
    void priorityComesBeforeTurns() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m050prio")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            JdbcPipelineJobRepository jobs = new JdbcPipelineJobRepository(jdbc);
            TenantId a = TenantId.generate();
            List<UUID> ofA = queue(jdbc, jobs, a, 3, false);
            queue(jdbc, jobs, TenantId.generate(), 3, false);
            PipelineJob first = jobs.claimNext(EnumSet.of(PipelineStage.TEXT), "a", Duration.ofMinutes(5), 1).orElseThrow();
            UUID urgent = ofA.get(2);
            jdbc.update("UPDATE pipeline_jobs SET priority = 'URGENT' WHERE id = :id",
                    new MapSqlParameterSource("id", urgent));
            assertEquals(urgent, jobs.claimNext(EnumSet.of(PipelineStage.TEXT), "a", Duration.ofMinutes(5), 1)
                    .orElseThrow().id(), "after " + first.tenantId());
        }
    }

    private static PipelineJob job(TenantId tenant, PipelineStage stage, PackageId pkg, DocumentId document) {
        return PipelineJob.ready(tenant, stage, pkg, document, null, null, 1, Instant.now());
    }
}
