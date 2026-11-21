package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.DestinationState;
import com.securedoc.platform.domain.pipeline.ExportDestinationStatus;
import com.securedoc.platform.domain.pipeline.ExportDestinations;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineJobStatus;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import com.securedoc.platform.application.port.out.PipelineJobRepository;
import com.securedoc.platform.application.port.out.SplitCopyRetentionPort;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0028: the new SQL against a real PostgreSQL (migrations 043 and 044 applied by Liquibase with the master
 * changelog). Runs only when {@code LEXAVAULT_TEST_PG_URL}, {@code LEXAVAULT_TEST_PG_USER} and
 * {@code LEXAVAULT_TEST_PG_PASSWORD} point at a throwaway database; the normal build skips it.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class JdbcExportFollowupsPostgresTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static NamedParameterJdbcTemplate jdbc;

    private JdbcPipelineJobRepository jobs;
    private JdbcExportDestinationStateRepository states;
    private JdbcExportReachabilityAlertRepository alerts;
    private JdbcExportRetentionRepository retention;
    private TenantId tenant;
    private IntegrationConnectionId destination;

    @BeforeAll
    static void migrate() throws Exception {
        String url = System.getenv("LEXAVAULT_TEST_PG_URL");
        String user = System.getenv("LEXAVAULT_TEST_PG_USER");
        String password = System.getenv("LEXAVAULT_TEST_PG_PASSWORD");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            new Liquibase("db/changelog/db.changelog-master.xml", new ClassLoaderResourceAccessor(), database)
                    .update(new Contexts());
        }
        jdbc = new NamedParameterJdbcTemplate(new DriverManagerDataSource(url, user, password));
    }

    @BeforeEach
    void setUp() {
        jdbc.getJdbcOperations().update("DELETE FROM pipeline_jobs");
        jdbc.getJdbcOperations().update("DELETE FROM export_destination_states");
        jobs = new JdbcPipelineJobRepository(jdbc);
        states = new JdbcExportDestinationStateRepository(jdbc);
        alerts = new JdbcExportReachabilityAlertRepository(jdbc);
        retention = new JdbcExportRetentionRepository(jdbc);
        tenant = TenantId.generate();
        destination = IntegrationConnectionId.generate();
        TestRows.tenant(jdbc, tenant);
        TestRows.connection(jdbc, tenant, destination);
    }

    private PipelineJob queue(PackageId pkg, IntegrationConnectionId to) {
        PipelineJob job = PipelineJob.ready(tenant, PipelineStage.EXPORT, pkg, null, to, null, 1, Instant.now());
        assertTrue(jobs.enqueue(job));
        return job;
    }

    private PackageId newCase() {
        PackageId pkg = PackageId.generate();
        TestRows.packageRow(jdbc, tenant, pkg);
        return pkg;
    }

    private Optional<PipelineJob> claim(String owner) {
        return jobs.claimNext(EnumSet.of(PipelineStage.EXPORT, PipelineStage.SPLIT_EXPORT), owner, LEASE, 4);
    }

    @Test
    void migration044AddsTheRetryFieldsAndAWorkerResultKeepsThem() {
        PackageId pkg = newCase();
        queue(pkg, destination);
        PipelineJob job = claim("w").orElseThrow();
        assertFalse(job.resend());
        assertFalse(job.rebuilt());
        assertEquals(0, job.claimExpiries());
        assertNull(job.firstFailedAt());

        assertTrue(jobs.finish(job.id(), job.claimToken(), new PipelineJobRepository.Finish(
                PipelineJobStatus.WAITING_RETRY, 1, Duration.ZERO, "LV-DLV-002", "DELIVERY_IO", true, true, true)));
        PipelineJob saved = jobs.findByTenantIdAndId(tenant, job.id()).orElseThrow();
        assertTrue(saved.resend());
        assertTrue(saved.rebuilt());
        assertNotNull(saved.firstFailedAt());
        assertNotNull(saved.lastFailedAt());
        Instant first = saved.firstFailedAt();

        // Due now, whatever small step the database VM's clock takes (Docker on Windows re-syncs it).
        jdbc.update("UPDATE pipeline_jobs SET next_attempt_at = clock_timestamp() - interval '1 minute'",
                new MapSqlParameterSource());
        PipelineJob again = claim("w").orElseThrow();
        jobs.finish(again.id(), again.claimToken(), new PipelineJobRepository.Finish(PipelineJobStatus.WAITING_RETRY, 2,
                Duration.ZERO, "LV-DLV-002", "DELIVERY_IO", false, false, true));
        PipelineJob later = jobs.findByTenantIdAndId(tenant, job.id()).orElseThrow();
        assertEquals(first, later.firstFailedAt(), "the first failure time stays");
        assertFalse(later.lastFailedAt().isBefore(first));
        assertTrue(later.rebuilt(), "once rebuilt, it stays rebuilt");
        assertFalse(later.resend());
    }

    @Test
    void recentlyFailingCasesAreCountedOncePerCase() {
        for (int i = 0; i < 3; i++) queue(newCase(), destination);
        UUID firstId = null;
        for (int i = 0; i < 2; i++) {
            PipelineJob job = claim("w").orElseThrow();
            if (firstId == null) firstId = job.id();
            jobs.finish(job.id(), job.claimToken(), new PipelineJobRepository.Finish(PipelineJobStatus.WAITING_RETRY,
                    1, Duration.ofHours(1), "LV-DLV-002", null, false, false, true));
        }
        // The same case failing again is still one case.
        jdbc.update("UPDATE pipeline_jobs SET next_attempt_at = clock_timestamp() WHERE id = :id",
                new MapSqlParameterSource("id", firstId));
        PipelineJob same = claim("w").orElseThrow();
        assertEquals(firstId, same.id());
        jobs.finish(same.id(), same.claimToken(), new PipelineJobRepository.Finish(PipelineJobStatus.WAITING_RETRY, 2,
                Duration.ofHours(1), "LV-DLV-002", null, false, false, true));
        assertEquals(2, jobs.countRecentlyFailingCases(tenant, destination, Duration.ofMinutes(10)));
        jdbc.update("UPDATE pipeline_jobs SET last_failed_at = clock_timestamp() - interval '11 minutes'",
                new MapSqlParameterSource());
        assertEquals(0, jobs.countRecentlyFailingCases(tenant, destination, Duration.ofMinutes(10)));
    }

    @Test
    void a429HoldsTheDestinationsJobsUntilRetryAfterAndTakesWorkersOffDownToOne() {
        for (int i = 0; i < 6; i++) queue(newCase(), destination);
        states.recordRateLimited(tenant, destination, Duration.ofMinutes(5), 3);
        assertTrue(claim("w").isEmpty(), "not before Retry-After");

        jdbc.update("UPDATE export_destination_states SET rate_limited_until = clock_timestamp()",
                new MapSqlParameterSource());
        List<PipelineJob> running = new ArrayList<>();
        Optional<PipelineJob> job;
        while ((job = claim("w")).isPresent()) running.add(job.get());
        assertEquals(3, running.size(), "4 workers less one");

        for (int i = 0; i < 5; i++) states.recordRateLimited(tenant, destination, Duration.ZERO, 3);
        ExportDestinationStatus slowed = states.find(tenant, destination).orElseThrow();
        assertEquals(3, slowed.workerReduction(), "never more than the limit less one");
        assertEquals(1, slowed.workerLimit(4));

        assertEquals(0, states.restoreWorkers(Duration.ofMinutes(10), 10), "not before 10 quiet minutes");
        jdbc.update("UPDATE export_destination_states SET last_rate_limited_at = clock_timestamp() - interval '11 minutes'",
                new MapSqlParameterSource());
        assertEquals(1, states.restoreWorkers(Duration.ofMinutes(10), 10));
        assertEquals(2, states.find(tenant, destination).orElseThrow().workerReduction());
        assertEquals(0, states.restoreWorkers(Duration.ofMinutes(10), 10), "the quiet time starts again");
    }

    @Test
    void resendAllPutsTheDestinationsWaitingJobsBackInOrderAndLeavesTheRestAlone() {
        IntegrationConnectionId other = IntegrationConnectionId.generate();
        TestRows.splitConnection(jdbc, tenant, other);
        List<UUID> waiting = new ArrayList<>();
        for (int i = 0; i < 3; i++) waiting.add(queue(newCase(), destination).id());
        UUID elsewhere = queue(newCase(), other).id();
        for (UUID id : waiting) jobs.changeStatus(tenant, id, PipelineJobStatus.READY, PipelineJobStatus.WAITING_FOR_YOU,
                1, "LV-DLV-004", null);
        jobs.changeStatus(tenant, elsewhere, PipelineJobStatus.READY, PipelineJobStatus.WAITING_FOR_YOU, 1,
                "LV-DLV-004", null);

        assertEquals(waiting, jobs.resendAll(tenant, destination));

        for (UUID id : waiting) {
            PipelineJob job = jobs.findByTenantIdAndId(tenant, id).orElseThrow();
            assertEquals(PipelineJobStatus.READY, job.status());
            assertTrue(job.resend());
        }
        assertEquals(PipelineJobStatus.WAITING_FOR_YOU, jobs.findByTenantIdAndId(tenant, elsewhere).orElseThrow().status());
        List<UUID> taken = new ArrayList<>();
        Optional<PipelineJob> job;
        while ((job = jobs.claimNext(EnumSet.of(PipelineStage.EXPORT), "w", LEASE, 10)).isPresent()) {
            taken.add(job.get().id());
        }
        assertEquals(waiting, taken, "in their original order");
    }

    @Test
    void openExportJobsAreCountedPerDestinationForSupportsWaitingColumn() {
        // Task 0029: open jobs only (DONE and STOPPED are not), per destination, this tenant only.
        IntegrationConnectionId other = IntegrationConnectionId.generate();
        TestRows.splitConnection(jdbc, tenant, other);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) ids.add(queue(newCase(), destination).id());
        queue(newCase(), other);
        jobs.changeStatus(tenant, ids.get(0), PipelineJobStatus.READY, PipelineJobStatus.DONE, 1, null, null);
        jobs.changeStatus(tenant, ids.get(1), PipelineJobStatus.READY, PipelineJobStatus.NEEDS_IT, 1, "LV-DLV-005", null);
        jobs.changeStatus(tenant, ids.get(2), PipelineJobStatus.READY, PipelineJobStatus.STOPPED, 1, null, null);

        var counts = jobs.countOpenByDestination(tenant);
        assertEquals(2, counts.get(destination.value()), "READY and NEEDS_IT are open; DONE and STOPPED are not");
        assertEquals(1, counts.get(other.value()));
        assertTrue(jobs.countOpenByDestination(TenantId.generate()).isEmpty(), "another tenant sees nothing");
    }

    @Test
    void needsItGroupsAreCountedPerDestinationAndCode() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) ids.add(queue(newCase(), destination).id());
        for (UUID id : ids) jobs.changeStatus(tenant, id, PipelineJobStatus.READY, PipelineJobStatus.NEEDS_IT, 1,
                "LV-DLV-006", null);
        assertEquals(2, jobs.countNeedsIt(tenant, destination, "LV-DLV-006", ids.get(0)));
        assertEquals(0, jobs.countNeedsIt(tenant, destination, "LV-DLV-002", ids.get(0)));
        assertEquals(0, jobs.countNeedsIt(tenant, null, "LV-DLV-006", ids.get(0)));
        assertEquals(3, jobs.findExportJobsByPackageIds(tenant, jobs.findByTenantIdAndStatus(tenant,
                PipelineJobStatus.NEEDS_IT, 10).stream().map(PipelineJob::packageId).toList()).size());
    }

    @Test
    void aClaimExpiryCountsAsAnAttemptAndOnItsOwnTowardsLvPrc008() {
        PackageId pkg = newCase();
        PipelineJob queued = queue(pkg, destination);
        // The job already used 4 tries on its own schedule; the claim limit counts claims that ran out only.
        jobs.changeStatus(tenant, queued.id(), PipelineJobStatus.READY, PipelineJobStatus.WAITING_RETRY, 4,
                "LV-DLV-002", "DELIVERY_IO");
        jdbc.update("UPDATE pipeline_jobs SET next_attempt_at = clock_timestamp()", new MapSqlParameterSource());
        PipelineJob job = claim("w").orElseThrow();
        jdbc.update("UPDATE pipeline_jobs SET claim_until = clock_timestamp() - interval '1 second'",
                new MapSqlParameterSource());
        PipelineJob released = jobs.releaseExpiredClaims(3, "LV-PRC-008", 10).get(0);
        assertEquals(job.id(), released.id());
        assertEquals(PipelineJobStatus.READY, released.status(), "one claim ran out, not three");
        assertEquals(5, released.attempts());
        assertEquals(1, released.claimExpiries());
    }

    @Test
    void networkWaitersAreMadeDueWhenTheAlertClears() {
        PipelineJob waited = queue(newCase(), destination);
        PipelineJob own = queue(newCase(), destination);
        jobs.changeStatus(tenant, waited.id(), PipelineJobStatus.READY, PipelineJobStatus.WAITING_RETRY, 0, "LV-DLV-001",
                "DESTINATIONS_UNREACHABLE");
        jobs.changeStatus(tenant, own.id(), PipelineJobStatus.READY, PipelineJobStatus.WAITING_RETRY, 1, "LV-DLV-001",
                "DELIVERY_IO");
        jdbc.update("UPDATE pipeline_jobs SET next_attempt_at = clock_timestamp() + interval '1 hour'",
                new MapSqlParameterSource());
        assertEquals(1, jobs.makeNetworkWaitersDueNow(tenant));
        assertEquals(waited.id(), claim("w").orElseThrow().id());
        assertTrue(claim("w").isEmpty());
    }

    @Test
    void theLocalFolderHasAStateRowThoughItIsNoConnectionAndAFailedCheckIsRecorded() {
        IntegrationConnectionId local = ExportDestinations.localFolder(tenant);
        states.recordCheckFailed(tenant, local);
        assertNotNull(states.find(tenant, local).orElseThrow().lastCheckFailedAt());
        assertTrue(states.transition(tenant, local, EnumSet.of(DestinationState.WORKING), DestinationState.PAUSED,
                "LV-DLV-001"));
        assertEquals(DestinationState.PAUSED, states.find(tenant, local).orElseThrow().state());
        PipelineJob job = queue(newCase(), local);
        assertTrue(claim("w").isEmpty(), "a paused local folder's jobs wait");
        assertTrue(states.transition(tenant, local, EnumSet.of(DestinationState.PAUSED), DestinationState.WORKING, null));
        assertEquals(job.id(), claim("w").orElseThrow().id());
    }

    @Test
    void theAlertIsRaisedOnceClearedOnceAndCheckedEachInterval() {
        assertTrue(alerts.raise(tenant));
        assertFalse(alerts.raise(tenant), "raised once");
        assertTrue(alerts.find(tenant).orElseThrow().raised());
        assertTrue(alerts.findRaisedDueForCheck(Duration.ofMinutes(5), 10).stream()
                .noneMatch(a -> a.tenantId().equals(tenant)), "checked after 5 minutes");
        assertTrue(alerts.findRaisedDueForCheck(Duration.ZERO, 10).stream().anyMatch(a -> a.tenantId().equals(tenant)));
        assertTrue(alerts.clear(tenant));
        assertFalse(alerts.clear(tenant), "cleared once");
        assertFalse(alerts.find(tenant).orElseThrow().raised());
        assertTrue(alerts.raise(tenant), "can be raised again later");
    }

    @Test
    void retentionPeriodsAreKeptPerDestinationAndTheDueSplitCopiesFollowThem() {
        IntegrationConnectionId split = IntegrationConnectionId.generate();
        TestRows.splitConnection(jdbc, tenant, split);
        ActorId admin = ActorId.generate();

        assertTrue(retention.masterHours(tenant).isEmpty());
        retention.setMaster(tenant, 10, admin);
        retention.setMaster(tenant, 12, admin);
        assertEquals(12, retention.masterHours(tenant).orElseThrow());
        retention.setSplit(tenant, split, 30, admin);
        assertEquals(30, retention.splitHours(tenant).get(split));

        PackageId pkg = newCase();
        UUID document = UUID.randomUUID();
        UUID artifact = UUID.randomUUID();
        UUID delivery = UUID.randomUUID();
        insertSplitCopy(pkg, document, artifact, delivery, split, Instant.now().minus(Duration.ofHours(31)));

        List<SplitCopyRetentionPort.SplitCopy> due = retention.findDueSplitCopies(Instant.now(), 100).stream()
                .filter(c -> c.tenantId().equals(tenant)).toList();
        assertEquals(1, due.size(), "31 h after receipt with a 30 h period");
        assertEquals(delivery, due.get(0).derivedDeliveryId());

        retention.setSplit(tenant, split, 40, admin);
        assertTrue(retention.findDueSplitCopies(Instant.now(), 100).stream().noneMatch(c -> c.tenantId().equals(tenant)),
                "its own period now runs 40 h");
        retention.setSplit(tenant, split, null, admin);
        assertEquals(1, retention.findDueSplitCopies(Instant.now(), 100).stream()
                .filter(c -> c.tenantId().equals(tenant)).count(), "back to the master's 12 h");

        // An open export job for the copy keeps it.
        PipelineJob open = PipelineJob.ready(tenant, PipelineStage.SPLIT_EXPORT, pkg, null, split, delivery, 1,
                Instant.now());
        jobs.enqueue(open);
        assertTrue(retention.findDueSplitCopies(Instant.now(), 100).stream().noneMatch(c -> c.tenantId().equals(tenant)));
        jobs.changeStatus(tenant, open.id(), PipelineJobStatus.READY, PipelineJobStatus.DONE, 1, null, null);
        assertEquals(1, retention.findDueSplitCopies(Instant.now(), 100).stream()
                .filter(c -> c.tenantId().equals(tenant)).count());

        // A deleted copy is not due again.
        jdbc.update("UPDATE artifacts SET status = 'DELETED' WHERE id = :id", new MapSqlParameterSource("id", artifact));
        assertTrue(retention.findDueSplitCopies(Instant.now(), 100).stream().noneMatch(c -> c.tenantId().equals(tenant)));

        retention.setMaster(tenant, null, admin);
        assertTrue(retention.masterHours(tenant).isEmpty());
        assertThrows(RuntimeException.class, () -> retention.setMaster(tenant, 73, admin), "1 to 72 hours");
    }

    private void insertSplitCopy(PackageId pkg, UUID document, UUID artifact, UUID delivery,
                                 IntegrationConnectionId split, Instant deliveredAt) {
        jdbc.update("""
                INSERT INTO documents (id, tenant_id, package_id, document_type, status, file_name, mime_type,
                                       size_bytes, checksum_algorithm, checksum_value, page_count, created_at,
                                       updated_at, version)
                VALUES (:id, :tenant, :pkg, 'PDF', 'PROCESSED', 'case.pdf', 'application/pdf', 3, 'SHA-256',
                        :sum, 1, now(), now(), 0)
                """, new MapSqlParameterSource("id", document).addValue("tenant", tenant.value())
                .addValue("pkg", pkg.value()).addValue("sum", "a".repeat(64)));
        jdbc.update("""
                INSERT INTO artifacts (id, tenant_id, document_id, artifact_type, status, mime_type, size_bytes,
                                       checksum_algorithm, checksum_value, created_at, updated_at, version)
                VALUES (:id, :tenant, :doc, 'DERIVED_REDACTED', 'AVAILABLE', 'application/pdf', 3, 'SHA-256', :sum,
                        now(), now(), 0)
                """, new MapSqlParameterSource("id", artifact).addValue("tenant", tenant.value())
                .addValue("doc", document).addValue("sum", "b".repeat(64)));
        jdbc.update("""
                INSERT INTO derived_deliveries (id, tenant_id, package_id, source_document_id, export_cycle,
                                                destination_connection_id, page_numbers, artifact_id,
                                                source_derived_document_id, status, attempt_count, retryable,
                                                created_at, updated_at, delivered_at)
                VALUES (:id, :tenant, :pkg, :doc, 1, :dest, '1', :artifact, :source, 'DELIVERED', 1, false,
                        now(), now(), :deliveredAt)
                """, new MapSqlParameterSource("id", delivery).addValue("tenant", tenant.value())
                .addValue("pkg", pkg.value()).addValue("doc", document).addValue("dest", split.value())
                .addValue("artifact", artifact).addValue("source", "derived-" + delivery)
                .addValue("deliveredAt", Timestamp.from(deliveredAt)));
    }
}
