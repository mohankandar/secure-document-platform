package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelinePriority;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.securedoc.platform.domain.pipeline.PipelinePriority.LOW;
import static com.securedoc.platform.domain.pipeline.PipelinePriority.NORMAL;
import static com.securedoc.platform.domain.pipeline.PipelinePriority.URGENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0073: priority in the queue against a real, throwaway PostgreSQL (migration 082). */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class JdbcCasePriorityPostgresTest {
    private static PostgresTestDatabase db;
    private static NamedParameterJdbcTemplate jdbc;
    private static TransactionTemplate tx;

    private JdbcPipelineJobRepository jobs;
    private JdbcCasePriorityRepository priorities;
    private TenantId tenant;

    @BeforeAll
    static void migrate() throws Exception {
        db = PostgresTestDatabase.migrated("CasePriority");
        jdbc = db.jdbc();
        tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        if (db != null) db.close();
    }

    @BeforeEach
    void setUp() {
        jdbc.getJdbcOperations().update("DELETE FROM pipeline_jobs");
        jobs = new JdbcPipelineJobRepository(jdbc);
        priorities = new JdbcCasePriorityRepository(jdbc);
        tenant = newTenant();
    }

    @Test
    void aCasesJobsHaveItsPriorityAndAChangeMovesWhatWaitsAtOnce() {
        PackageId first = queue(tenant, NORMAL);
        PackageId second = queue(tenant, NORMAL);
        PackageId urgent = queue(tenant, URGENT);
        assertEquals(Map.of(first, NORMAL, urgent, URGENT), priorities.find(tenant, List.of(first, urgent)));
        assertEquals(NORMAL, tx.execute(s -> priorities.change(tenant, second, LOW)));
        assertEquals(LOW, tx.execute(s -> priorities.change(tenant, second, URGENT)));
        // Task 0121: the two Urgent cases take turns (round and round by case), so either may go first; then Normal.
        assertEquals(java.util.Set.of(second, urgent), java.util.Set.of(take(), take()), "Urgent first");
        assertEquals(first, take());
        assertEquals(URGENT, jobs.findByTenantIdAndPackageId(tenant, second).get(0).priority());
        assertThrows(IllegalStateException.class, () -> priorities.change(tenant, first, LOW), "outside a transaction");
    }

    @Test
    void aRunningJobFinishesAndItsNextJobsHaveTheNewPriority() {
        PackageId pkg = queue(tenant, NORMAL);
        PipelineJob running = claim().orElseThrow();
        tx.execute(s -> priorities.change(tenant, pkg, URGENT));
        assertTrue(jobs.claimIsCurrent(running.id(), running.claimToken()), "not taken away");
        assertTrue(jobs.enqueue(PipelineJob.ready(tenant, PipelineStage.TEXT, pkg, DocumentId.generate(), null, null, 2,
                Instant.now())));
        assertEquals(URGENT, jobs.findByTenantIdAndPackageId(tenant, pkg).get(1).priority());
        assertEquals(URGENT, jobs.findByTenantIdAndPackageId(tenant, pkg).get(0).priority(), "if it comes back");
    }

    @Test
    void aJobQueuedWhileThePriorityChangesIsNotLeftBehind() throws Exception {
        PackageId pkg = caseRow(tenant);
        CountDownLatch queued = new CountDownLatch(1);
        CountDownLatch goOn = new CountDownLatch(1);
        var slow = CompletableFuture.runAsync(() -> tx.execute(s -> {
            jobs.enqueue(job(tenant, pkg));
            queued.countDown();
            try {
                goOn.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        queued.await();
        var change = CompletableFuture.runAsync(() -> tx.execute(s -> priorities.change(tenant, pkg, URGENT)));
        Thread.sleep(300);
        assertFalse(change.isDone(), "waits for the job being queued");
        goOn.countDown();
        slow.get();
        change.get();
        assertEquals(URGENT, jobs.findByTenantIdAndPackageId(tenant, pkg).get(0).priority());
    }

    /** A change never waits for a step that is saving its job (which may be waiting to queue the next one). */
    @Test
    void aChangeDoesNotWaitForAJobBeingSaved() throws Exception {
        PackageId pkg = queue(tenant, NORMAL);
        PipelineJob running = claim().orElseThrow();
        CountDownLatch saved = new CountDownLatch(1);
        CountDownLatch changed = new CountDownLatch(1);
        var step = CompletableFuture.runAsync(() -> tx.execute(s -> {
            jdbc.update("UPDATE pipeline_jobs SET status = 'DONE', claim_owner = NULL, claim_until = NULL WHERE id = :id",
                    new MapSqlParameterSource("id", running.id()));
            saved.countDown();
            try {
                changed.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        saved.await();
        CompletableFuture.runAsync(() -> tx.execute(s -> priorities.change(tenant, pkg, LOW))).get(5, TimeUnit.SECONDS);
        changed.countDown();
        step.get();
    }

    @Test
    void oneInFiveGoesToNormalWhileUrgentWaitsAndToLowWhileNormalWaits() {
        for (int i = 0; i < 40; i++) {
            queue(tenant, URGENT);
            queue(tenant, NORMAL);
            queue(tenant, LOW);
        }
        List<PipelinePriority> taken = new ArrayList<>();
        for (int i = 0; i < 120; i++) taken.add(claim().orElseThrow().priority());
        assertEquals(List.of(URGENT, URGENT, URGENT, URGENT, NORMAL), taken.subList(0, 5));
        for (int from = 0; from + 5 <= 45; from++) {
            List<PipelinePriority> five = taken.subList(from, from + 5);
            assertEquals(4, five.stream().filter(p -> p == URGENT).count(), "any five while both wait: " + five);
        }
        assertEquals(8, taken.subList(0, 40).stream().filter(p -> p != URGENT).count());
        int lastUrgent = taken.lastIndexOf(URGENT);
        List<PipelinePriority> rest = taken.subList(lastUrgent + 1, 120);
        for (int from = 0; from + 5 <= 30; from++) {
            assertEquals(1, rest.subList(from, from + 5).stream().filter(p -> p == LOW).count(), "Low against Normal");
        }
        assertTrue(claim().isEmpty());
    }

    @Test
    void entitiesTakeTurnsWithinAPriority() {
        TenantId other = newTenant();
        for (int i = 0; i < 6; i++) queue(tenant, URGENT);
        for (int i = 0; i < 2; i++) queue(other, URGENT);
        List<TenantId> first = new ArrayList<>();
        for (int i = 0; i < 4; i++) first.add(claim().orElseThrow().tenantId());
        assertEquals(2, first.stream().filter(other::equals).count(), "one entity's flood does not push the other back");
    }

    @Test
    void anUnknownPriorityIsRefusedByTheTableAndSourcesHaveDefaults() {
        assertEquals(Optional.empty(), priorities.sourceDefault(tenant, "folder:installed"));
        priorities.saveSourceDefault(tenant, "folder:installed", LOW);
        priorities.saveSourceDefault(tenant, "folder:installed", URGENT);
        assertEquals(Map.of("folder:installed", URGENT), priorities.sourceDefaults(tenant));
        assertThrows(IllegalArgumentException.class,
                () -> priorities.saveNew(tenant, PackageId.generate(), PipelinePriority.BULK, null));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> priorities.saveSourceDefault(tenant, "x", PipelinePriority.BULK));
    }

    // ------------------------------------------------------- task 0121: cases take turns inside an entity

    /**
     * Task 0121 (owner 2026-11-28): a 120-page case queued first (six page batches), a 5-page case a moment later in the
     * same entity: the small case's job is taken second, not seventh. Another entity still gets its turn, and Urgent
     * still goes first.
     */
    @Test
    void casesTakeTurnsInsideAnEntityAndUrgentAndOtherEntitiesStillComeFirst() {
        PackageId big = caseRow(tenant);
        priorities.saveNew(tenant, big, NORMAL, null);
        for (int batch = 0; batch < 6; batch++) assertTrue(jobs.enqueue(job(tenant, big)));
        PackageId small = queue(tenant, NORMAL);

        PackageId firstTaken = take();
        PackageId secondTaken = take();
        assertEquals(java.util.Set.of(big, small), java.util.Set.of(firstTaken, secondTaken),
                "the small case is taken within the first two, not after the big case's six batches");
        for (int i = 0; i < 5; i++) assertEquals(big, take());
        assertTrue(claim().isEmpty());

        // Another entity's case and an Urgent case, behind a long queue of the first entity.
        for (int batch = 0; batch < 6; batch++) assertTrue(jobs.enqueue(job(tenant, big)));
        TenantId other = newTenant();
        PackageId othersCase = queue(other, NORMAL);
        PackageId urgent = queue(tenant, URGENT);
        assertEquals(urgent, take(), "Urgent first");
        List<PackageId> next = List.of(take(), take());
        assertTrue(next.contains(othersCase), "the other entity gets its turn at once: " + next);
    }

    /**
     * Task 0121 part 2.1: the claim with case turns on a 10,000-job queue (200 cases, 3 entities): the real candidate
     * query's plan and the claim time, printed for the report. It must stay quick (here: under 250 ms a claim).
     */
    @Test
    void theClaimWithCaseTurnsStaysQuickOnATenThousandJobQueue() {
        List<TenantId> entities = List.of(tenant, newTenant(), newTenant());
        List<PackageId> cases = new java.util.ArrayList<>();
        for (int c = 0; c < 200; c++) {
            PackageId pkg = caseRow(entities.get(c % 3));
            priorities.saveNew(entities.get(c % 3), pkg, NORMAL, null);
            cases.add(pkg);
        }
        for (int c = 0; c < 200; c++) {
            TenantId owner = entities.get(c % 3);
            PackageId pkg = cases.get(c);
            jdbc.update("""
                    INSERT INTO pipeline_jobs (id, tenant_id, stage, package_id, document_id, export_cycle, priority, status,
                        attempts, next_attempt_at, claim_token, created_at, updated_at)
                    SELECT gen_random_uuid(), :t, 'TEXT', :p, gen_random_uuid(), 1, 'NORMAL', 'READY', 0, now(), 0, now(), now()
                      FROM generate_series(1, 50)
                    """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("t", owner.value())
                    .addValue("p", pkg.value()));
        }
        jdbc.getJdbcOperations().execute("ANALYZE pipeline_jobs");
        assertEquals(10_000, jdbc.queryForObject("SELECT count(*) FROM pipeline_jobs WHERE status = 'READY'",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(), Integer.class));

        for (int i = 0; i < 5; i++) take(); // a few cases served, so the case turns have something to rotate
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("stages", List.of("TEXT")).addValue("perDestination", 4)
                .addValue("everyBulk", true).addValue("someBulk", false).addValue("openBulk", List.of("-"))
                .addValue("urgentRank", 1).addValue("normalRank", 2).addValue("lowRank", 3)
                .addValue("lastTenant", tenant.value())
                .addValue("servedTenants", entities.stream().map(e -> e.value().toString())
                        .collect(java.util.stream.Collectors.joining(",")))
                .addValue("servedCases", cases.subList(0, 3).stream().map(p -> p.value().toString())
                        .collect(java.util.stream.Collectors.joining(",")))
                .addValue("noCase", new java.util.UUID(0L, 0L)).addValue("caseTurns", true);
        List<String> plan = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) "
                + JdbcPipelineJobRepository.CANDIDATE_SQL, params, String.class);
        System.out.println("TASK0121_EXPLAIN_BEGIN");
        plan.forEach(line -> System.out.println("TASK0121_EXPLAIN " + line));
        long started = System.nanoTime();
        int claims = 20;
        for (int i = 0; i < claims; i++) take();
        long perClaimMs = (System.nanoTime() - started) / 1_000_000 / claims;
        System.out.println("TASK0121_CLAIM_MS " + perClaimMs);
        assertTrue(perClaimMs < 250, "one claim took " + perClaimMs + " ms on a 10,000-job queue");
    }

    private TenantId newTenant() {
        TenantId id = TenantId.generate();
        TestRows.tenant(jdbc, id);
        return id;
    }

    private PackageId caseRow(TenantId owner) {
        PackageId pkg = PackageId.generate();
        TestRows.packageRow(jdbc, owner, pkg);
        return pkg;
    }

    private PackageId queue(TenantId owner, PipelinePriority priority) {
        PackageId pkg = caseRow(owner);
        priorities.saveNew(owner, pkg, priority, null);
        assertTrue(jobs.enqueue(job(owner, pkg)));
        return pkg;
    }

    private static PipelineJob job(TenantId owner, PackageId pkg) {
        return PipelineJob.ready(owner, PipelineStage.TEXT, pkg, DocumentId.generate(), null, null, 1, Instant.now());
    }

    private Optional<PipelineJob> claim() {
        return jobs.claimNext(Set.of(PipelineStage.TEXT), "w", Duration.ofMinutes(5), 4);
    }

    private PackageId take() {
        return claim().orElseThrow().packageId();
    }
}
