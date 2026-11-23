package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.application.port.out.ExportDestinationStateRepository;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.DestinationState;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineJobStatus;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0027: the export job SQL against a real PostgreSQL (migration 043 applied by Liquibase with the master
 * changelog). Runs only when {@code LEXAVAULT_TEST_PG_URL}, {@code LEXAVAULT_TEST_PG_USER} and
 * {@code LEXAVAULT_TEST_PG_PASSWORD} point at a throwaway database; the normal build skips it.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class JdbcPipelineJobRepositoryPostgresTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static PostgresTestDatabase db;
    private static NamedParameterJdbcTemplate jdbc;

    private JdbcPipelineJobRepository jobs;
    private JdbcExportDestinationStateRepository states;
    private TenantId tenant;
    private IntegrationConnectionId destination;

    @BeforeAll
    static void migrate() throws Exception {
        // Task 0038: its own database, so no other test class sees or changes its rows.
        db = PostgresTestDatabase.migrated("PipelineJobRepository");
        jdbc = db.jdbc();
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        if (db != null) db.close();
    }

    @BeforeEach
    void setUp() {
        // The pick-up query serves every tenant (as in production), so each test starts with an empty queue.
        jdbc.getJdbcOperations().update("DELETE FROM pipeline_jobs");
        jdbc.getJdbcOperations().update("DELETE FROM export_destination_states");
        jobs = new JdbcPipelineJobRepository(jdbc);
        states = new JdbcExportDestinationStateRepository(jdbc);
        tenant = TenantId.generate();
        destination = IntegrationConnectionId.generate();
        TestRows.tenant(jdbc, tenant);
        TestRows.connection(jdbc, tenant, destination);
    }

    private PackageId queueCase(int cycle) {
        PackageId pkg = PackageId.generate();
        TestRows.packageRow(jdbc, tenant, pkg);
        assertTrue(jobs.enqueue(PipelineJob.ready(tenant, PipelineStage.EXPORT, pkg, null, destination, null, cycle,
                Instant.now())));
        return pkg;
    }

    private Optional<PipelineJob> claim(String owner) {
        return jobs.claimNext(EnumSet.of(PipelineStage.EXPORT, PipelineStage.SPLIT_EXPORT), owner, LEASE, 4);
    }

    private void expire(UUID id) {
        jdbc.update("UPDATE pipeline_jobs SET claim_until = clock_timestamp() - interval '1 second' WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    private void dueNow(UUID id) {
        jdbc.update("UPDATE pipeline_jobs SET next_attempt_at = clock_timestamp() WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    @Test
    void queuingTheSameCaseRunTwiceAddsNothing() {
        PackageId pkg = queueCase(1);
        assertFalse(jobs.enqueue(PipelineJob.ready(tenant, PipelineStage.EXPORT, pkg, null, destination, null, 1,
                Instant.now())));
        assertTrue(jobs.enqueue(PipelineJob.ready(tenant, PipelineStage.EXPORT, pkg, null, destination, null, 2,
                Instant.now())), "a new case run is a new job");
        assertEquals(2, jobs.findByTenantIdAndPackageId(tenant, pkg).size());
    }

    @Test
    void twoWorkersAtOnceNeverTakeTheSameJob() throws Exception {
        int count = 30;
        for (int i = 0; i < count; i++) queueCase(1);
        // Every job may run at once here: no per-destination limit in the way.
        jdbc.update("UPDATE pipeline_jobs SET destination_id = NULL WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant.value()));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<UUID>>> futures = new ArrayList<>();
        for (int w = 0; w < 8; w++) {
            String owner = "worker-" + w;
            futures.add(pool.submit(() -> {
                start.await();
                List<UUID> taken = new ArrayList<>();
                Optional<PipelineJob> job;
                while ((job = claim(owner)).isPresent()) taken.add(job.get().id());
                return taken;
            }));
        }
        start.countDown();
        List<UUID> all = Collections.synchronizedList(new ArrayList<>());
        for (Future<List<UUID>> f : futures) all.addAll(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(count, all.size(), "every job taken");
        assertEquals(count, new HashSet<>(all).size(), "no job taken twice");
    }

    @Test
    void pickUpIsInOrderAndRespectsTheDueTimeAndThePerDestinationLimit() {
        List<PackageId> cases = new ArrayList<>();
        for (int i = 0; i < 6; i++) cases.add(queueCase(1));
        List<PackageId> taken = new ArrayList<>();
        for (int i = 0; i < 4; i++) taken.add(claim("w").orElseThrow().packageId());
        assertEquals(cases.subList(0, 4), taken, "oldest first");
        assertTrue(claim("w").isEmpty(), "4 per destination at most");

        PipelineJob first = jobs.findByTenantIdAndPackageId(tenant, cases.get(0)).get(0);
        assertTrue(jobs.finish(first.id(), first.claimToken(), PipelineJobStatus.WAITING_RETRY, 1,
                Duration.ofMinutes(5), "LV-DLV-001", "DELIVERY_IO"));
        assertEquals(cases.get(4), claim("w").orElseThrow().packageId(), "a job waiting to retry is not due yet");
    }

    @Test
    void aPausedDestinationsJobsAreNotTakenAndComeBackInOrder() {
        List<PackageId> cases = new ArrayList<>();
        for (int i = 0; i < 3; i++) cases.add(queueCase(1));
        for (int i = 0; i < 3; i++) {
            ExportDestinationStateRepository.TransientFailure failure =
                    states.recordTransientFailure(tenant, destination, 3, "LV-DLV-001");
            assertEquals(i == 2, failure.pausedNow());
        }
        assertEquals(DestinationState.PAUSED, states.find(tenant, destination).orElseThrow().state());
        assertTrue(claim("w").isEmpty(), "nothing taken while paused");
        assertTrue(states.findPausedDueForProbe(Duration.ofMinutes(5), 10).isEmpty(), "probed after 5 minutes");
        assertEquals(1, states.findPausedDueForProbe(Duration.ZERO, 10).size());

        assertTrue(states.transition(tenant, destination, EnumSet.of(DestinationState.PAUSED), DestinationState.WORKING,
                null));
        assertEquals(0, states.find(tenant, destination).orElseThrow().consecutiveFailures());
        List<PackageId> taken = new ArrayList<>();
        Optional<PipelineJob> job;
        while ((job = claim("w")).isPresent()) taken.add(job.get().packageId());
        assertEquals(cases, taken);
    }

    @Test
    void onlyTheCurrentClaimCanSaveAndAnExpiredClaimComesBackAsAnAttempt() {
        PackageId pkg = queueCase(1);
        PipelineJob stale = claim("server-1").orElseThrow();
        assertTrue(jobs.renew(stale.id(), stale.claimToken(), LEASE));
        expire(stale.id());
        assertFalse(jobs.claimIsCurrent(stale.id(), stale.claimToken()));
        assertFalse(jobs.finish(stale.id(), stale.claimToken(), PipelineJobStatus.DONE, 1, null, null, null),
                "a claim that ran out can not save");

        List<PipelineJob> released = jobs.releaseExpiredClaims(3, "LV-PRC-008", 10);
        assertEquals(1, released.size());
        assertEquals(PipelineJobStatus.READY, released.get(0).status());
        assertEquals(1, released.get(0).attempts(), "the lost run counts as an attempt");

        PipelineJob current = claim("server-2").orElseThrow();
        assertEquals(stale.claimToken() + 1, current.claimToken());
        assertFalse(jobs.renew(stale.id(), stale.claimToken(), LEASE), "the old token can not renew");
        assertFalse(jobs.finish(stale.id(), stale.claimToken(), PipelineJobStatus.DONE, 2, null, null, null));
        assertTrue(jobs.finish(current.id(), current.claimToken(), PipelineJobStatus.DONE, 2, null, null, null));
        assertEquals(PipelineJobStatus.DONE, jobs.findByTenantIdAndPackageId(tenant, pkg).get(0).status());
    }

    @Test
    void aClaimThatRunsOutOnTheLastAttemptGoesToIt() {
        queueCase(1);
        PipelineJob job = null;
        for (int i = 0; i < 3; i++) {
            job = claim("w").orElseThrow();
            expire(job.id());
            jobs.releaseExpiredClaims(3, "LV-PRC-008", 10);
        }
        PipelineJob after = jobs.findByTenantIdAndId(tenant, job.id()).orElseThrow();
        assertEquals(PipelineJobStatus.NEEDS_IT, after.status());
        assertEquals("LV-PRC-008", after.lastLvCode());
    }

    @Test
    void resendClaimAndItRetryWorkOnlyFromTheirStatus() {
        PackageId pkg = queueCase(1);
        PipelineJob job = claim("w").orElseThrow();
        assertTrue(jobs.finish(job.id(), job.claimToken(), PipelineJobStatus.WAITING_FOR_YOU, 1, null, "LV-DLV-004",
                "DELIVERY_DESTINATION_INVALID"));
        assertTrue(claim("w").isEmpty(), "waiting for a person is never picked up");
        assertTrue(jobs.findByTenantIdAndStatus(tenant, PipelineJobStatus.WAITING_FOR_YOU, 10).stream()
                .anyMatch(j -> j.packageId().equals(pkg)));
        assertTrue(jobs.claim(tenant, job.id(), EnumSet.of(PipelineJobStatus.NEEDS_IT), "it", LEASE).isEmpty());
        PipelineJob resend = jobs.claim(tenant, job.id(), EnumSet.of(PipelineJobStatus.WAITING_FOR_YOU), "resend",
                LEASE).orElseThrow();
        assertTrue(jobs.finish(resend.id(), resend.claimToken(), PipelineJobStatus.NEEDS_IT, 2, null, "LV-DLV-015",
                "LV-DLV-004"));
        assertFalse(jobs.changeStatus(tenant, job.id(), PipelineJobStatus.WAITING_FOR_YOU, PipelineJobStatus.READY, 0,
                null, null));
        assertTrue(jobs.changeStatus(tenant, job.id(), PipelineJobStatus.NEEDS_IT, PipelineJobStatus.READY, 0,
                "LV-DLV-015", "LV-DLV-004"));
        assertEquals(job.id(), claim("w").orElseThrow().id());
    }

    @Test
    void resumingADestinationMakesTheJobsThatWaitedForItDueNowButNotTheOnesOnTheirOwnSchedule() {
        // Task 0028: only jobs that waited because of the destination are made due; a case on its own retry schedule
        // keeps it.
        PackageId waited = queueCase(1);
        PackageId ownSchedule = queueCase(1);
        PipelineJob job = claim("w").orElseThrow();
        jobs.finish(job.id(), job.claimToken(), PipelineJobStatus.WAITING_RETRY, 1, Duration.ofHours(1), "LV-DLV-001",
                "DESTINATION_PAUSED");
        PipelineJob other = claim("w").orElseThrow();
        jobs.finish(other.id(), other.claimToken(), PipelineJobStatus.WAITING_RETRY, 1, Duration.ofHours(1),
                "LV-DLV-002", "DELIVERY_IO");
        assertTrue(claim("w").isEmpty());
        assertEquals(1, jobs.makeDueNow(tenant, destination));
        assertEquals(waited, claim("w").orElseThrow().packageId());
        assertTrue(claim("w").isEmpty(), "the case on its own schedule still waits");
        assertNotEquals(waited, ownSchedule);
    }

    @Test
    void aCaseWithAnOpenExportJobIsNotPickedUpByProcessingRecovery() {
        PackageId waiting = queueCase(1);
        PackageId done = queueCase(1);
        PipelineJob doneJob = jobs.findByTenantIdAndPackageId(tenant, done).get(0);
        jdbc.update("UPDATE pipeline_jobs SET status = 'DONE' WHERE id = :id", new MapSqlParameterSource("id", doneJob.id()));
        jdbc.update("UPDATE document_packages SET status = 'PROCESSING', updated_at = now() - interval '1 hour'"
                + " WHERE tenant_id = :t", new MapSqlParameterSource("t", tenant.value()));
        var lease = new com.securedoc.platform.infrastructure.processing.JdbcPackageProcessingLeaseAdapter(jdbc);

        Set<UUID> orphans = new HashSet<>();
        lease.findProcessingWithoutLease(Duration.ofSeconds(1), 1000).forEach(o -> orphans.add(o.packageId().value()));

        assertFalse(orphans.contains(waiting.value()), "Ready for export is not interrupted work");
        assertTrue(orphans.contains(done.value()), "a case without an open export job is recovered as before");
        dueNow(doneJob.id());
    }
}
