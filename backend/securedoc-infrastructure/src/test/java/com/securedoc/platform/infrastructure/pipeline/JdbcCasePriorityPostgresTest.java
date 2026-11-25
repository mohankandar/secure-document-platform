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
        assertEquals(List.of(second, urgent, first), List.of(take(), take(), take()), "in queue order within Urgent");
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
