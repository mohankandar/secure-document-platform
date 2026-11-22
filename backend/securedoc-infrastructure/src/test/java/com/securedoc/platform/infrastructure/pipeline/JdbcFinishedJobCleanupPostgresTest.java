package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0034: the clean-up SQL against a real PostgreSQL (the master changelog applied by Liquibase). Runs only when
 * {@code LEXAVAULT_TEST_PG_URL}, {@code LEXAVAULT_TEST_PG_USER} and {@code LEXAVAULT_TEST_PG_PASSWORD} point at a
 * throwaway database; the normal build skips it.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class JdbcFinishedJobCleanupPostgresTest {
    private static final int KEEP_DAYS = 90;
    private static PostgresTestDatabase db;
    private static NamedParameterJdbcTemplate jdbc;

    private JdbcPipelineJobRepository jobs;
    private JdbcExportReachabilityAlertRepository alerts;
    private JdbcFinishedJobCleanupRepository cleanup;
    private TenantId tenant;
    private IntegrationConnectionId destination;
    private int cycle;

    @BeforeAll
    static void migrate() throws Exception {
        // Task 0038: its own database, so no other test class sees or changes its rows.
        db = PostgresTestDatabase.migrated("FinishedJobCleanup");
        jdbc = db.jdbc();
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        if (db != null) db.close();
    }

    @BeforeEach
    void setUp() {
        jdbc.getJdbcOperations().update("DELETE FROM pipeline_jobs");
        jdbc.getJdbcOperations().update("DELETE FROM export_reachability_alerts");
        jobs = new JdbcPipelineJobRepository(jdbc);
        alerts = new JdbcExportReachabilityAlertRepository(jdbc);
        cleanup = new JdbcFinishedJobCleanupRepository(jdbc);
        tenant = newTenant();
        destination = IntegrationConnectionId.generate();
        TestRows.connection(jdbc, tenant, destination);
        cycle = 0;
    }

    // ---------------------------------------------------------------- rows

    private static TenantId newTenant() {
        TenantId t = TenantId.generate();
        TestRows.tenant(jdbc, t);
        return t;
    }

    /** A case; {@code purged}: its final retention purge ran; {@code hidden}: it is off the Cases list. */
    private PackageId newCase(TenantId owner, boolean purged, boolean hidden) {
        PackageId pkg = PackageId.generate();
        TestRows.packageRow(jdbc, owner, pkg);
        jdbc.update("UPDATE document_packages SET status = 'COMPLETED', dashboard_hidden_at = "
                        + (hidden ? "now()" : "NULL") + " WHERE id = :id",
                new MapSqlParameterSource("id", pkg.value()));
        jdbc.update("""
                INSERT INTO package_lifecycles (package_id, tenant_id, successful_export_cycles, purged_at,
                                                final_approval_granted, updated_at, version)
                VALUES (:id, :tenant, 1, :purgedAt, false, now(), 0)
                """, new MapSqlParameterSource("id", pkg.value()).addValue("tenant", owner.value())
                .addValue("purgedAt", purged ? java.sql.Timestamp.from(Instant.now()) : null));
        return pkg;
    }

    private PackageId newCase(boolean purged, boolean hidden) {
        return newCase(tenant, purged, hidden);
    }

    /** A job in this status, last changed this many days ago. */
    private UUID job(TenantId owner, PackageId pkg, String status, int ageDays) {
        PipelineJob job = PipelineJob.ready(owner, PipelineStage.EXPORT, pkg, null,
                owner.equals(tenant) ? destination : null, null, ++cycle, Instant.now());
        assertTrue(jobs.enqueue(job));
        jdbc.update("UPDATE pipeline_jobs SET status = :status,"
                        + " updated_at = clock_timestamp() - (:days * interval '1 day') WHERE id = :id",
                new MapSqlParameterSource("status", status).addValue("days", ageDays).addValue("id", job.id()));
        return job.id();
    }

    private UUID job(PackageId pkg, String status, int ageDays) {
        return job(tenant, pkg, status, ageDays);
    }

    private void storedCopy(PackageId pkg, String type, String status) {
        UUID document = UUID.randomUUID();
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
                VALUES (:id, :tenant, :doc, :type, :status, 'application/pdf', 3, 'SHA-256', :sum, now(), now(), 0)
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("tenant", tenant.value())
                .addValue("doc", document).addValue("type", type).addValue("status", status)
                .addValue("sum", "b".repeat(64)));
    }

    private Instant cutoff() {
        return Instant.now().minus(Duration.ofDays(KEEP_DAYS));
    }

    private boolean exists(UUID jobId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM pipeline_jobs WHERE id = :id",
                new MapSqlParameterSource("id", jobId), Integer.class);
        return n != null && n == 1;
    }

    private int removeAll() {
        int total = 0;
        int removed;
        do {
            removed = cleanup.deleteFinishedJobs(tenant, cutoff(), 1000);
            total += removed;
        } while (removed == 1000);
        return total;
    }

    // ---------------------------------------------------------------- tests

    @Test
    void oldDoneAndStoppedJobsAreRemovedAndRecentOnesKept() {
        UUID oldDone = job(newCase(true, true), "DONE", 91);
        UUID oldStopped = job(newCase(true, true), "STOPPED", 400);
        UUID recentDone = job(newCase(true, true), "DONE", 89);
        UUID recentStopped = job(newCase(true, true), "STOPPED", 1);

        assertEquals(2, removeAll());

        assertFalse(exists(oldDone));
        assertFalse(exists(oldStopped));
        assertTrue(exists(recentDone));
        assertTrue(exists(recentStopped));
    }

    @Test
    void openJobsOfAnyAgeAreNeverRemoved() {
        List<String> open = List.of("READY", "RUNNING", "WAITING_RETRY", "PAUSED", "WAITING_FOR_YOU", "NEEDS_IT");
        List<UUID> ids = open.stream().map(s -> job(newCase(true, true), s, 3000)).toList();

        assertEquals(0, removeAll());

        for (int i = 0; i < ids.size(); i++) assertTrue(exists(ids.get(i)), open.get(i));
    }

    @Test
    void aFinishedJobOfACaseWithAnOpenJobIsKept() {
        PackageId pkg = newCase(true, true);
        UUID done = job(pkg, "DONE", 200);
        UUID needsIt = job(pkg, "NEEDS_IT", 200);

        assertEquals(0, removeAll());
        assertTrue(exists(done));
        assertTrue(exists(needsIt));
    }

    @Test
    void jobsOfACaseStillInsideItsRetentionAreKept() {
        UUID notPurged = job(newCase(false, false), "DONE", 200);
        UUID notPurgedStopped = job(newCase(false, false), "STOPPED", 200);

        PackageId noLifecycle = PackageId.generate();
        TestRows.packageRow(jdbc, tenant, noLifecycle);
        UUID withoutLifecycle = job(noLifecycle, "STOPPED", 200);

        PackageId splitStillStored = newCase(true, true);
        storedCopy(splitStillStored, "DERIVED_REDACTED", "AVAILABLE");
        UUID splitKept = job(splitStillStored, "DONE", 200);

        PackageId masterStillStored = newCase(true, true);
        storedCopy(masterStillStored, "REDACTED", "AVAILABLE");
        UUID masterKept = job(masterStillStored, "STOPPED", 200);

        PackageId copiesDeleted = newCase(true, true);
        storedCopy(copiesDeleted, "REDACTED", "DELETED");
        storedCopy(copiesDeleted, "DERIVED_REDACTED", "DELETED");
        storedCopy(copiesDeleted, "EXTRACTED_TEXT", "AVAILABLE");
        UUID removable = job(copiesDeleted, "DONE", 200);

        assertEquals(1, removeAll());

        assertTrue(exists(notPurged));
        assertTrue(exists(notPurgedStopped));
        assertTrue(exists(withoutLifecycle));
        assertTrue(exists(splitKept), "a split copy still stored keeps its case's jobs");
        assertTrue(exists(masterKept));
        assertFalse(exists(removable), "deleted copies and other artifacts do not keep the job");
    }

    @Test
    void aDoneJobOfACaseStillOnTheCasesListIsKeptSoItStillShowsExported() {
        PackageId visible = newCase(true, false);
        UUID done = job(visible, "DONE", 200);
        UUID stopped = job(visible, "STOPPED", 200);

        assertEquals(1, removeAll());
        assertTrue(exists(done), "the case keeps its \"Exported\" chip");
        assertFalse(exists(stopped), "a STOPPED job does not change what the case shows");
    }

    @Test
    void removesInBatchesOldestFirstAndOnlyThisTenantsJobs() {
        List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) ids.add(job(newCase(true, true), "DONE", 100 + (5 - i)));
        TenantId other = newTenant();
        UUID othersJob = job(other, newCase(other, true, true), "DONE", 300);

        assertEquals(2, cleanup.deleteFinishedJobs(tenant, cutoff(), 2));
        assertFalse(exists(ids.get(0)), "oldest first");
        assertFalse(exists(ids.get(1)));
        assertTrue(exists(ids.get(2)));
        assertEquals(2, cleanup.deleteFinishedJobs(tenant, cutoff(), 2));
        assertEquals(1, cleanup.deleteFinishedJobs(tenant, cutoff(), 2));
        assertEquals(0, cleanup.deleteFinishedJobs(tenant, cutoff(), 2));
        assertTrue(exists(othersJob), "another tenant's jobs wait for that tenant's turn");
        assertTrue(cleanup.tenantIds().contains(other));
        assertEquals(1, cleanup.deleteFinishedJobs(other, cutoff(), 2));
    }

    @Test
    void theCaseAndItsHistoryStayWhenItsJobsAreRemoved() {
        PackageId pkg = newCase(true, true);
        job(pkg, "DONE", 200);
        assertEquals(1, removeAll());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM document_packages WHERE id = :id",
                new MapSqlParameterSource("id", pkg.value()), Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM package_lifecycles WHERE package_id = :id",
                new MapSqlParameterSource("id", pkg.value()), Integer.class));
    }

    @Test
    void clearedAlertsOlderThanTheCutOffAreRemovedAndTheAlertStillWorksAfterwards() {
        TenantId recent = newTenant();
        TenantId raised = newTenant();
        assertTrue(alerts.raise(tenant));
        assertTrue(alerts.clear(tenant));
        jdbc.update("UPDATE export_reachability_alerts SET cleared_at = clock_timestamp() - interval '91 days'"
                + " WHERE tenant_id = :t", new MapSqlParameterSource("t", tenant.value()));
        assertTrue(alerts.raise(recent));
        assertTrue(alerts.clear(recent));
        assertTrue(alerts.raise(raised));
        jdbc.update("UPDATE export_reachability_alerts SET raised_at = clock_timestamp() - interval '400 days'"
                + " WHERE tenant_id = :t", new MapSqlParameterSource("t", raised.value()));

        assertEquals(1, cleanup.deleteClearedAlerts(tenant, cutoff()));
        assertEquals(0, cleanup.deleteClearedAlerts(recent, cutoff()), "cleared recently");
        assertEquals(0, cleanup.deleteClearedAlerts(raised, cutoff()), "still raised");
        assertTrue(alerts.find(tenant).isEmpty());
        assertTrue(alerts.find(recent).isPresent());
        assertTrue(alerts.find(raised).orElseThrow().raised());

        assertTrue(alerts.raise(tenant), "the alert can be raised again after its row was removed");
        assertTrue(alerts.find(tenant).orElseThrow().raised());
    }

    /**
     * A tenant with 20,000 cases and jobs as they are after 90 days of use: 18,000 finished within the keep time,
     * 1,000 open, 1,000 finished before the cut-off. With fresh statistics and no planner settings changed, the delete
     * reads the jobs through {@code idx_pipeline_jobs_tenant_status}, never by scanning the whole table.
     */
    @Test
    void theDeleteUsesTheTenantStatusIndex() {
        MapSqlParameterSource p = new MapSqlParameterSource("tenant", tenant.value()).addValue("actor", UUID.randomUUID());
        jdbc.update("""
                INSERT INTO document_packages (id, tenant_id, package_type, status, created_by, created_at, updated_at,
                                               dashboard_hidden_at)
                SELECT md5('pkg' || CAST(:tenant AS text) || g)::uuid, :tenant, 'MANUAL_UPLOAD', 'COMPLETED', :actor, now(),
                       now(), CASE WHEN g > 19000 THEN now() END
                  FROM generate_series(1, 20000) g
                """, p);
        jdbc.update("""
                INSERT INTO package_lifecycles (package_id, tenant_id, successful_export_cycles, purged_at,
                                                final_approval_granted, updated_at, version)
                SELECT md5('pkg' || CAST(:tenant AS text) || g)::uuid, :tenant, 1, CASE WHEN g > 19000 THEN now() END,
                       false, now(), 0
                  FROM generate_series(1, 20000) g
                """, p);
        jdbc.update("""
                INSERT INTO pipeline_jobs (id, tenant_id, stage, package_id, export_cycle, priority, status, attempts,
                                           next_attempt_at, claim_token, created_at, updated_at)
                SELECT gen_random_uuid(), :tenant, 'EXPORT', md5('pkg' || CAST(:tenant AS text) || g)::uuid, 1, 'NORMAL',
                       CASE WHEN g <= 18000 THEN 'DONE' WHEN g <= 19000 THEN 'READY' ELSE 'DONE' END, 1, now(), 0,
                       now(), CASE WHEN g <= 19000 THEN now() - ((g % 89) * interval '1 day')
                                   ELSE now() - interval '120 days' END
                  FROM generate_series(1, 20000) g
                """, p);
        String sql = JdbcFinishedJobCleanupRepository.DELETE_FINISHED_JOBS_SQL
                .replace(":tenantId", "'" + tenant.value() + "'::uuid")
                .replace(":cutoff", "'" + cutoff() + "'::timestamptz")
                .replace(":limit", "1000");
        String plan = jdbc.getJdbcOperations().execute((ConnectionCallback<String>) connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute("ANALYZE pipeline_jobs, document_packages, package_lifecycles, documents, artifacts");
                StringBuilder out = new StringBuilder();
                try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
                    while (rs.next()) out.append(rs.getString(1)).append('\n');
                }
                return out.toString();
            }
        });
        assertNotNull(plan);
        assertTrue(plan.matches("(?s).*(Index|Index Only|Bitmap Index) Scan (using|on) idx_pipeline_jobs_tenant_status.*"), plan);
        assertFalse(plan.contains("Seq Scan on pipeline_jobs"), plan);

        assertEquals(1000, cleanup.deleteFinishedJobs(tenant, cutoff(), 1000), "the 1,000 finished before the cut-off");
        assertEquals(0, cleanup.deleteFinishedJobs(tenant, cutoff(), 1000));
        assertEquals(19_000, jdbc.queryForObject("SELECT count(*) FROM pipeline_jobs WHERE tenant_id = :tenant", p,
                Integer.class));
    }
}
