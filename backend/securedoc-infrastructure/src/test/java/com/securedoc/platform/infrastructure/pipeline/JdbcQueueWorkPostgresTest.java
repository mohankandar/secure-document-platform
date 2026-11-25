package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.application.pipeline.QueueView;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0078: the numbers of the queue against a real, throwaway PostgreSQL. */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class JdbcQueueWorkPostgresTest {

    @Test
    void theNumbersOfOneEntityAndOfAllOfThem() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("queuework")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            JdbcQueueWork queue = new JdbcQueueWork(jdbc);
            TenantId a = TenantId.generate();
            TenantId b = TenantId.generate();
            TestRows.tenant(jdbc, a);
            TestRows.tenant(jdbc, b);
            PackageId open = PackageId.generate();
            PackageId waitingCase = PackageId.generate();
            PackageId inReview = PackageId.generate();
            PackageId other = PackageId.generate();
            for (PackageId p : new PackageId[]{open, waitingCase, inReview}) TestRows.packageRow(jdbc, a, p);
            TestRows.packageRow(jdbc, b, other);
            status(jdbc, waitingCase, "SUBMITTED");
            status(jdbc, inReview, "PENDING_REVIEW");
            UUID reading = document(jdbc, a, open, "EXTRACTING");
            UUID searching = document(jdbc, a, open, "DETECTING");
            UUID redacting = document(jdbc, a, open, "REDACTING");
            document(jdbc, a, waitingCase, "UPLOADED");
            document(jdbc, a, waitingCase, "UPLOADED");
            document(jdbc, a, inReview, "REDACTING");
            UUID theirs = document(jdbc, b, other, "DETECTING");

            job(jdbc, a, open, reading, "TEXT", "RUNNING", 100, 0);
            job(jdbc, a, open, searching, "FIND", "READY", 90, 0);
            job(jdbc, a, open, searching, "VISION", "READY", 80, 0, 1);
            job(jdbc, a, open, redacting, "REDACT", "READY", 5, 0);
            // Finished Find jobs: two that overlap (100 s to 40 s ago) and one apart (200 s to 180 s ago); 3 jobs of 2 documents.
            job(jdbc, a, open, searching, "FIND", "DONE", 100, 60, 1);
            job(jdbc, a, open, redacting, "FIND", "DONE", 80, 40, 2);
            job(jdbc, a, open, redacting, "FIND", "DONE", 200, 180, 3);
            // Finished before the window.
            job(jdbc, a, open, reading, "FIND", "DONE", 3000, 2000, 4);
            job(jdbc, b, other, theirs, "FIND", "READY", 400, 0);
            job(jdbc, b, other, null, "EXPORT", "PAUSED", 400, 0);

            Map<PipelineStage, QueueView.Work> mine = queue.work(a, QueueView.WINDOW);
            QueueView.Work text = mine.get(PipelineStage.TEXT);
            assertEquals(1, text.running());
            assertEquals(2, text.toCome(), "the documents of the case not started");
            assertNull(text.oldestWaitingSeconds(), "a running job is not waiting");
            QueueView.Work find = mine.get(PipelineStage.FIND);
            assertEquals(1, find.waiting());
            assertEquals(90, find.oldestWaitingSeconds(), 2);
            assertEquals(3, find.done());
            // Waiting since 90 s ago, so busy from 100 s ago until now, and 20 s before that.
            assertEquals(120, find.busySeconds(), 2);
            assertEquals(Math.round(3 * 1.5f), find.toCome(), "3 documents not read yet, 1.5 jobs each of late");
            QueueView.Work redact = mine.get(PipelineStage.REDACT);
            assertEquals(1, redact.waiting());
            assertEquals(4, redact.toCome(), "2 not started, 1 read, 1 searched; the one in Review waits for a person");
            assertEquals(2, mine.get(PipelineStage.EXPORT).toCome());
            assertEquals(0, mine.get(PipelineStage.EXPORT).held());

            Map<PipelineStage, QueueView.Work> all = queue.work(null, QueueView.WINDOW);
            assertEquals(2, all.get(PipelineStage.FIND).waiting());
            assertEquals(400, all.get(PipelineStage.FIND).oldestWaitingSeconds(), 2);
            assertEquals(1, all.get(PipelineStage.EXPORT).held());
            assertEquals(5, all.get(PipelineStage.REDACT).toCome());
            assertTrue(queue.work(b, QueueView.WINDOW).get(PipelineStage.TEXT).running() == 0);
        }
    }

    @Test
    void theLastMinuteSaysThePaceOnlyWithEnoughJobs() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("queuepace")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId tenant = TenantId.generate();
            TestRows.tenant(jdbc, tenant);
            PackageId pkg = PackageId.generate();
            TestRows.packageRow(jdbc, tenant, pkg);
            UUID doc = document(jdbc, tenant, pkg, "DETECTING");
            for (int page = 1; page <= 19; page++) job(jdbc, tenant, pkg, doc, "FIND", "DONE", 300, 5, page);
            QueueView.Work find = new JdbcQueueWork(jdbc).work(tenant, QueueView.WINDOW).get(PipelineStage.FIND);
            assertEquals(19, find.done());
            assertEquals(295, find.busySeconds(), 2);
            job(jdbc, tenant, pkg, doc, "FIND", "DONE", 300, 5, 20);
            job(jdbc, tenant, pkg, doc, "FIND", "DONE", 300, 200, 21);
            find = new JdbcQueueWork(jdbc).work(tenant, QueueView.WINDOW).get(PipelineStage.FIND);
            assertEquals(20, find.done(), "only the last minute's");
            assertEquals(55, find.busySeconds(), 2);
        }
    }

    private static void status(NamedParameterJdbcTemplate jdbc, PackageId id, String status) {
        jdbc.update("update document_packages set status = :s where id = :id",
                new MapSqlParameterSource("s", status).addValue("id", id.value()));
    }

    private static UUID document(NamedParameterJdbcTemplate jdbc, TenantId tenant, PackageId pkg, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO documents (id, tenant_id, package_id, document_type, status, file_name, mime_type,
                                       size_bytes, checksum_algorithm, checksum_value, page_count, created_at,
                                       updated_at, version)
                VALUES (:id, :tenant, :pkg, 'PDF', :status, 'case.pdf', 'application/pdf', 3, 'SHA-256',
                        :sum, 1, now(), now(), 0)
                """, new MapSqlParameterSource("id", id).addValue("tenant", tenant.value()).addValue("pkg", pkg.value())
                .addValue("status", status).addValue("sum", "a".repeat(64)));
        return id;
    }

    private static void job(NamedParameterJdbcTemplate jdbc, TenantId tenant, PackageId pkg, UUID document, String stage,
                            String status, int createdAgo, int endedAgo) {
        job(jdbc, tenant, pkg, document, stage, status, createdAgo, endedAgo, null);
    }

    private static void job(NamedParameterJdbcTemplate jdbc, TenantId tenant, PackageId pkg, UUID document, String stage,
                            String status, int createdAgo, int endedAgo, Integer page) {
        jdbc.update("""
                INSERT INTO pipeline_jobs (id, tenant_id, stage, package_id, document_id, export_cycle, priority, status,
                        attempts, next_attempt_at, claim_token, created_at, updated_at, page_from, page_to)
                VALUES (gen_random_uuid(), :tenant, :stage, :pkg, :doc, 1, 'NORMAL', :status, 0, clock_timestamp(), 0,
                        clock_timestamp() - :created * interval '1 second', clock_timestamp() - :ended * interval '1 second',
                        :page, :page)
                """, new MapSqlParameterSource("tenant", tenant.value()).addValue("stage", stage).addValue("pkg", pkg.value())
                .addValue("doc", document, java.sql.Types.OTHER).addValue("status", status).addValue("created", createdAgo)
                .addValue("ended", endedAgo).addValue("page", page, java.sql.Types.INTEGER));
    }
}
