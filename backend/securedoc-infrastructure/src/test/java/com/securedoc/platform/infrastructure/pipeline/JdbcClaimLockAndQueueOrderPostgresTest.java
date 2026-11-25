package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import liquibase.Contexts;
import liquibase.Liquibase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0036 against a real PostgreSQL: the per-destination worker limit holds when workers claim at the same moment
 * (finding 1 of task 0035), and the queue order is {@code queue_seq}, never the clock (finding 2; migration 045).
 * Runs only when {@code LEXAVAULT_TEST_PG_URL}, {@code LEXAVAULT_TEST_PG_USER} and {@code LEXAVAULT_TEST_PG_PASSWORD}
 * point at a throwaway database; the normal build skips it. The migration test creates and drops its own database
 * next to it.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class JdbcClaimLockAndQueueOrderPostgresTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final EnumSet<PipelineStage> EXPORT = EnumSet.of(PipelineStage.EXPORT, PipelineStage.SPLIT_EXPORT);
    private static PostgresTestDatabase db;
    private static NamedParameterJdbcTemplate jdbc;

    private JdbcPipelineJobRepository jobs;
    private TenantId tenant;
    private IntegrationConnectionId destination;

    @BeforeAll
    static void migrate() throws Exception {
        // Task 0038: its own database, so no other test class sees or changes its rows.
        db = PostgresTestDatabase.migrated("ClaimLockAndQueueOrder");
        jdbc = db.jdbc();
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        if (db != null) db.close();
    }

    @BeforeEach
    void setUp() {
        jdbc.getJdbcOperations().update("DELETE FROM pipeline_jobs");
        jdbc.getJdbcOperations().update("DELETE FROM export_destination_states");
        jobs = new JdbcPipelineJobRepository(jdbc);
        tenant = TenantId.generate();
        destination = IntegrationConnectionId.generate();
        TestRows.tenant(jdbc, tenant);
        TestRows.connection(jdbc, tenant, destination);
    }

    private PipelineJob queue(IntegrationConnectionId to) {
        PackageId pkg = PackageId.generate();
        TestRows.packageRow(jdbc, tenant, pkg);
        PipelineJob job = PipelineJob.ready(tenant, PipelineStage.EXPORT, pkg, null, to, null, 1, Instant.now());
        assertTrue(jobs.enqueue(job));
        return job;
    }

    private int running(IntegrationConnectionId at) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM pipeline_jobs WHERE destination_id = :d AND status = 'RUNNING'",
                new MapSqlParameterSource("d", at.value()), Integer.class);
        return n == null ? 0 : n;
    }

    /** {@code workers} threads claim at the same moment, once each, with the given per-destination limit. */
    private List<PipelineJob> claimAtOnce(int workers, int limit) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CyclicBarrier together = new CyclicBarrier(workers);
        List<Future<Optional<PipelineJob>>> futures = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            String owner = "worker-" + w;
            futures.add(pool.submit(() -> {
                together.await(10, TimeUnit.SECONDS);
                return jobs.claimNext(EXPORT, owner, LEASE, limit);
            }));
        }
        List<PipelineJob> claimed = new ArrayList<>();
        for (Future<Optional<PipelineJob>> f : futures) f.get(60, TimeUnit.SECONDS).ifPresent(claimed::add);
        pool.shutdown();
        return claimed;
    }

    // ---------------------------------------------------------------- 1. the per-destination limit under concurrency

    @Test
    void workersClaimingAtTheSameMomentNeverRunMoreThanTheLimitForOneDestination() throws Exception {
        for (int i = 0; i < 4; i++) queue(destination);
        // Many rounds: before task 0036 two workers both saw 0 running and both claimed (0035, 4 workers).
        for (int round = 0; round < 25; round++) {
            jdbc.getJdbcOperations().update("UPDATE pipeline_jobs SET status = 'READY', claim_owner = NULL,"
                    + " claim_until = NULL, next_attempt_at = clock_timestamp()");
            List<PipelineJob> claimed = claimAtOnce(8, 1);
            assertEquals(1, claimed.size(), "round " + round + ": one claim, the others found the destination full");
            assertEquals(1, running(destination), "round " + round + ": only one RUNNING");
        }
    }

    @Test
    void aLimitOfTwoAllowsExactlyTwoAtOnce() throws Exception {
        for (int i = 0; i < 5; i++) queue(destination);
        for (int round = 0; round < 10; round++) {
            jdbc.getJdbcOperations().update("UPDATE pipeline_jobs SET status = 'READY', claim_owner = NULL,"
                    + " claim_until = NULL, next_attempt_at = clock_timestamp()");
            List<PipelineJob> claimed = claimAtOnce(8, 2);
            assertEquals(2, running(destination), "round " + round);
            assertEquals(2, claimed.size(), "round " + round);
        }
    }

    @Test
    void anotherDestinationIsNotHeldUpByAFullOne() throws Exception {
        IntegrationConnectionId other = IntegrationConnectionId.generate();
        TestRows.splitConnection(jdbc, tenant, other);
        List<PipelineJob> first = new ArrayList<>();
        for (int i = 0; i < 3; i++) first.add(queue(destination));
        List<PipelineJob> second = new ArrayList<>();
        for (int i = 0; i < 3; i++) second.add(queue(other));

        // Workers at once: one job per destination, each the first of its queue.
        List<PipelineJob> claimed = claimAtOnce(8, 1);
        assertEquals(2, claimed.size());
        Set<UUID> ids = new HashSet<>();
        claimed.forEach(j -> ids.add(j.id()));
        assertEquals(Set.of(first.get(0).id(), second.get(0).id()), ids);
        assertEquals(1, running(destination));
        assertEquals(1, running(other));

        // One destination full, the other's next job is still taken at once.
        jdbc.update("UPDATE pipeline_jobs SET status = 'DONE' WHERE id = :id", new MapSqlParameterSource("id",
                second.get(0).id()));
        PipelineJob next = jobs.claimNext(EXPORT, "w", LEASE, 1).orElseThrow();
        assertEquals(second.get(1).id(), next.id());
        assertTrue(jobs.claimNext(EXPORT, "w", LEASE, 1).isEmpty(), "both full now");
    }

    @Test
    void manyWorkersDrainingOneDestinationNeverRunTwoAtOnce() throws Exception {
        int count = 12;
        for (int i = 0; i < count; i++) queue(destination);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        List<Integer> overLimit = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int w = 0; w < 4; w++) {
            String owner = "worker-" + w;
            futures.add(pool.submit(() -> {
                start.await();
                int done = 0;
                long until = System.currentTimeMillis() + 30_000;
                while (System.currentTimeMillis() < until) {
                    Optional<PipelineJob> job = jobs.claimNext(EXPORT, owner, LEASE, 1);
                    if (job.isEmpty()) {
                        Integer left = jdbc.queryForObject("SELECT count(*) FROM pipeline_jobs WHERE status <> 'DONE'",
                                new MapSqlParameterSource(), Integer.class);
                        if (left != null && left == 0) break;
                        continue;
                    }
                    int now = running(destination);
                    if (now > 1) overLimit.add(now);
                    jdbc.update("UPDATE pipeline_jobs SET status = 'DONE', claim_owner = NULL, claim_until = NULL"
                            + " WHERE id = :id", new MapSqlParameterSource("id", job.get().id()));
                    done++;
                }
                return done;
            }));
        }
        start.countDown();
        int total = 0;
        for (Future<Integer> f : futures) total += f.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals(count, total, "every job ran once");
        assertTrue(overLimit.isEmpty(), "never two RUNNING at once: " + overLimit);
    }

    @Test
    void withALimitOfOneADestinationsJobsGoOutInQueueOrderWhenWorkersClaimAtOnce() throws Exception {
        // Task 0038: before, the worker that won the destination's lock took the row it had locked first, which could
        // be the destination's second job while the first was held by a worker still waiting for the lock.
        List<PipelineJob> queued = new ArrayList<>();
        for (int i = 0; i < 6; i++) queued.add(queue(destination));
        for (int cycle = 0; cycle < 5; cycle++) {
            jdbc.getJdbcOperations().update("UPDATE pipeline_jobs SET status = 'READY', claim_owner = NULL,"
                    + " claim_until = NULL");
            for (int i = 0; i < queued.size(); i++) {
                List<PipelineJob> claimed = claimAtOnce(8, 1);
                assertEquals(List.of(queued.get(i).id()), claimed.stream().map(PipelineJob::id).toList(),
                        "cycle " + cycle + ", job " + i + ": the destination's first waiting job, and only that one");
                jdbc.update("UPDATE pipeline_jobs SET status = 'DONE', claim_owner = NULL, claim_until = NULL"
                        + " WHERE id = :id", new MapSqlParameterSource("id", queued.get(i).id()));
            }
        }
    }

    @Test
    void jobsWithoutADestinationHaveNoLimitAndEachIsTakenOnce() throws Exception {
        List<UUID> queued = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            PackageId pkg = PackageId.generate();
            TestRows.packageRow(jdbc, tenant, pkg);
            PipelineJob job = PipelineJob.ready(tenant, PipelineStage.EXPORT, pkg, null, null, null, 1, Instant.now());
            assertTrue(jobs.enqueue(job));
            queued.add(job.id());
        }
        List<PipelineJob> claimed = claimAtOnce(8, 1);
        assertEquals(new HashSet<>(queued), new HashSet<>(claimed.stream().map(PipelineJob::id).toList()));
        assertEquals(3, claimed.size(), "each once");
    }

    // ---------------------------------------------------------------- 1b. READY is due whatever the clock says

    @Test
    void aReadyJobIsTakenEvenWhenTheDatabaseClockIsBehindItsTime() {
        // Task 0038: a READY job is written with the clock of that moment; when the clock then steps back (13 ms in
        // this task's runs on Docker), the job looked "not due yet" and the claim returned nothing.
        PipelineJob job = queue(destination);
        jdbc.update("UPDATE pipeline_jobs SET next_attempt_at = clock_timestamp() + interval '1 minute' WHERE id = :id",
                new MapSqlParameterSource("id", job.id()));
        assertEquals(job.id(), jobs.claimNext(EXPORT, "w", LEASE, 1).orElseThrow().id());
    }

    @Test
    void aJobWaitingToRetryStillWaitsForItsTime() {
        PipelineJob job = queue(destination);
        jdbc.update("UPDATE pipeline_jobs SET status = 'WAITING_RETRY',"
                + " next_attempt_at = clock_timestamp() + interval '1 minute' WHERE id = :id",
                new MapSqlParameterSource("id", job.id()));
        assertTrue(jobs.claimNext(EXPORT, "w", LEASE, 1).isEmpty(), "not due yet");
        jdbc.update("UPDATE pipeline_jobs SET next_attempt_at = clock_timestamp() - interval '1 second' WHERE id = :id",
                new MapSqlParameterSource("id", job.id()));
        assertEquals(job.id(), jobs.claimNext(EXPORT, "w", LEASE, 1).orElseThrow().id());
    }

    // ---------------------------------------------------------------- 2. queue order is queue_seq, not the clock

    @Test
    void pickUpFollowsTheQueueNumberEvenWhenCreatedAtIsOutOfOrder() {
        List<PipelineJob> queued = new ArrayList<>();
        for (int i = 0; i < 4; i++) queued.add(queue(destination));
        // The database clock stepped back: later jobs got earlier created_at values (task 0035, finding 2).
        for (int i = 0; i < 4; i++) {
            jdbc.update("UPDATE pipeline_jobs SET created_at = clock_timestamp() - (:minutes * interval '1 minute')"
                    + " WHERE id = :id", new MapSqlParameterSource("id", queued.get(i).id()).addValue("minutes", i * 10));
        }
        List<UUID> taken = new ArrayList<>();
        Optional<PipelineJob> job;
        while ((job = jobs.claimNext(EXPORT, "w", LEASE, 10)).isPresent()) taken.add(job.get().id());
        assertEquals(queued.stream().map(PipelineJob::id).toList(), taken, "queue order, not created_at");
        assertEquals(queued.stream().map(PipelineJob::id).toList(),
                jobs.findExportJobsByPackageIds(tenant, queued.stream().map(PipelineJob::packageId).toList())
                        .stream().map(PipelineJob::id).toList(), "listed in queue order too");
    }

    @Test
    void resendAllGoesOutInQueueOrderEvenWhenCreatedAtIsOutOfOrder() {
        List<PipelineJob> queued = new ArrayList<>();
        for (int i = 0; i < 3; i++) queued.add(queue(destination));
        for (int i = 0; i < 3; i++) {
            jdbc.update("UPDATE pipeline_jobs SET status = 'WAITING_FOR_YOU',"
                    + " created_at = clock_timestamp() - (:minutes * interval '1 minute') WHERE id = :id",
                    new MapSqlParameterSource("id", queued.get(i).id()).addValue("minutes", i * 10));
        }
        assertEquals(queued.stream().map(PipelineJob::id).toList(), jobs.resendAll(tenant, destination));
    }

    @Test
    void everyNewJobGetsAHigherQueueNumber() {
        List<Long> numbers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            PipelineJob job = queue(destination);
            numbers.add(jdbc.queryForObject("SELECT queue_seq FROM pipeline_jobs WHERE id = :id",
                    new MapSqlParameterSource("id", job.id()), Long.class));
        }
        for (int i = 1; i < numbers.size(); i++) assertTrue(numbers.get(i) > numbers.get(i - 1), numbers.toString());
    }

    // ---------------------------------------------------------------- migration 045 on existing rows

    @Test
    void migration045NumbersExistingJobsInTheirCreatedAtOrder() throws Exception {
        try (PostgresTestDatabase m045 = PostgresTestDatabase.empty("m045")) {
            try (Connection connection = m045.connection()) {
                Liquibase liquibase = PostgresTestDatabase.liquibase(connection);
                liquibase.update(new Contexts());
                PostgresTestDatabase.rollback(liquibase, connection, 16); // back to 044 (082, 081, 071, 080, 070, 064, 063, 062, 061, 060, 050, 049, 048, 047, 046, then 045): no queue_seq
            }
            NamedParameterJdbcTemplate own = m045.jdbc();
            Boolean hasColumn = own.queryForObject("SELECT EXISTS (SELECT 1 FROM information_schema.columns"
                    + " WHERE table_name = 'pipeline_jobs' AND column_name = 'queue_seq')", new MapSqlParameterSource(),
                    Boolean.class);
            assertEquals(Boolean.FALSE, hasColumn, "rolled back to 044");

            TenantId t = TenantId.generate();
            TestRows.tenant(own, t);
            // Inserted in one order, created_at in another: the numbering follows created_at, then id.
            int[] minutesAgo = {5, 30, 10, 20};
            List<UUID> ids = new ArrayList<>();
            for (int ago : minutesAgo) {
                PackageId pkg = PackageId.generate();
                TestRows.packageRow(own, t, pkg);
                UUID id = UUID.randomUUID();
                ids.add(id);
                own.update("""
                        INSERT INTO pipeline_jobs (id, tenant_id, stage, package_id, export_cycle, priority, status,
                            attempts, next_attempt_at, claim_token, created_at, updated_at)
                        VALUES (:id, :t, 'EXPORT', :p, 1, 'NORMAL', 'READY', 0, now(), 0,
                                now() - (:ago * interval '1 minute'), now())
                        """, new MapSqlParameterSource("id", id).addValue("t", t.value()).addValue("p", pkg.value())
                        .addValue("ago", ago));
            }

            try (Connection connection = m045.connection()) {
                PostgresTestDatabase.liquibase(connection).update(new Contexts());
            }

            List<UUID> byNumber = own.query("SELECT id FROM pipeline_jobs ORDER BY queue_seq",
                    new MapSqlParameterSource(), (rs, n) -> rs.getObject("id", UUID.class));
            assertEquals(List.of(ids.get(1), ids.get(3), ids.get(2), ids.get(0)), byNumber, "oldest created_at first");
            assertEquals(List.of(1L, 2L, 3L, 4L), own.query("SELECT queue_seq FROM pipeline_jobs ORDER BY queue_seq",
                    new MapSqlParameterSource(), (rs, n) -> rs.getLong(1)));
            assertEquals("NO", own.queryForObject("SELECT is_nullable FROM information_schema.columns"
                    + " WHERE table_name = 'pipeline_jobs' AND column_name = 'queue_seq'", new MapSqlParameterSource(),
                    String.class), "NOT NULL afterwards");
            assertEquals(Integer.valueOf(1), own.queryForObject("SELECT count(*) FROM pg_indexes"
                    + " WHERE tablename = 'pipeline_jobs' AND indexname = 'idx_pipeline_jobs_pickup'"
                    + " AND indexdef LIKE '%queue_seq%'", new MapSqlParameterSource(), Integer.class),
                    "the pick-up index uses queue_seq");

            // A job queued after the migration comes after every existing one.
            JdbcPipelineJobRepository ownJobs = new JdbcPipelineJobRepository(own);
            PackageId pkg = PackageId.generate();
            TestRows.packageRow(own, t, pkg);
            PipelineJob later = PipelineJob.ready(t, PipelineStage.EXPORT, pkg, null, null, null, 1, Instant.now());
            assertTrue(ownJobs.enqueue(later));
            assertEquals(Long.valueOf(5), own.queryForObject("SELECT queue_seq FROM pipeline_jobs WHERE id = :id",
                    new MapSqlParameterSource("id", later.id()), Long.class));
            List<UUID> taken = new ArrayList<>();
            Optional<PipelineJob> job;
            while ((job = ownJobs.claimNext(EXPORT, "w", LEASE, 4)).isPresent()) taken.add(job.get().id());
            List<UUID> expected = new ArrayList<>(byNumber);
            expected.add(later.id());
            assertEquals(expected, taken);
        }
    }
}
