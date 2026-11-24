package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.api.processing.ExportJobWorkers;
import com.securedoc.platform.application.intelligence.service.DocumentIntelligenceExecutionService;
import com.securedoc.platform.application.port.in.DetectSensitiveDataUseCase;
import com.securedoc.platform.application.port.in.ExtractDocumentTextUseCase;
import com.securedoc.platform.application.port.in.RedactDocumentUseCase;
import com.securedoc.platform.application.port.in.StartDocumentProcessingUseCase;
import com.securedoc.platform.application.port.in.StartPackageProcessingUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.ProcessingLockPort;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.processing.service.TriggerPackageProcessingService;
import com.securedoc.platform.application.resilience.SelectiveRetryExecutor;
import com.securedoc.platform.application.telemetry.ProductTelemetryService;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.pipeline.DestinationState;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineJobStatus;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import com.securedoc.platform.domain.routing.DerivedDelivery;
import com.securedoc.platform.domain.routing.DerivedDeliveryStatus;
import com.securedoc.platform.infrastructure.pipeline.JdbcExportDestinationStateRepository;
import com.securedoc.platform.infrastructure.pipeline.JdbcExportReachabilityAlertRepository;
import com.securedoc.platform.infrastructure.pipeline.JdbcPipelineJobRepository;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 0052: export-mode goes from job back to inline while exports are queued. Real PostgreSQL for the queue, the
 * destination states and the alert; the real export service, job service, workers and inline trigger; a stand-in
 * destination that counts every call it accepts. Its own database, dropped afterwards. Synthetic data only.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class ExportBackToInlinePostgresTest {
    private static String adminUrl;
    private static String user;
    private static String password;
    private static String dbName;
    private static JdbcTemplate jdbc;
    private static JdbcPipelineJobRepository jobs;
    private static JdbcExportDestinationStateRepository states;
    private static JdbcExportReachabilityAlertRepository alerts;

    private static final ExportJobSettings JOB = settings(ExportMode.JOB);
    private static final ExportJobSettings INLINE = settings(ExportMode.INLINE);

    private ExportJobFixture f;
    private ExportJobQueue inlineQueue;
    private ExportJobService inlineService;

    /** A check every second. After the switch one job per destination at a time; before it two (one server stopped). */
    private static ExportJobSettings settings(ExportMode mode) {
        return new ExportJobSettings(mode, Duration.ofMinutes(5), 3, 3, Duration.ofSeconds(1), Duration.ofSeconds(30),
                mode == ExportMode.JOB ? 2 : 1, 2);
    }

    @BeforeAll
    static void start() throws Exception {
        adminUrl = System.getenv("LEXAVAULT_TEST_PG_URL");
        user = System.getenv("LEXAVAULT_TEST_PG_USER");
        password = System.getenv("LEXAVAULT_TEST_PG_PASSWORD");
        dbName = "lv_t_backinline_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); Statement s = admin.createStatement()) {
            s.execute("CREATE DATABASE " + dbName);
        }
        String url = adminUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + dbName + "$1");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            new Liquibase("db/changelog/db.changelog-master.xml", new ClassLoaderResourceAccessor(),
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)))
                    .update(new Contexts());
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
        jdbc = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(dataSource);
        jobs = new JdbcPipelineJobRepository(named, new DataSourceTransactionManager(dataSource));
        states = new JdbcExportDestinationStateRepository(named);
        alerts = new JdbcExportReachabilityAlertRepository(named);
    }

    @AfterAll
    static void stop() throws Exception {
        if (dbName == null) return;
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); Statement s = admin.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
        }
    }

    @BeforeEach
    void wire() {
        jdbc.update("DELETE FROM pipeline_jobs");
        f = new ExportJobFixture(JOB, jobs, states, alerts);
        inlineQueue = f.queueFor(INLINE);
        inlineService = f.serviceFor(INLINE);
        jdbc.update("INSERT INTO tenants (id, name, slug, status, created_at, updated_at)"
                + " VALUES (?, 'Back to inline', ?, 'ACTIVE', now(), now())", f.tenant.value(),
                "back-" + UUID.randomUUID().toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO integration_connections
                    (id, tenant_id, name, purpose, direction, provider_type, authentication_type, timeout_seconds,
                     retry_max_attempts, acknowledgement_mode, enabled, configuration_version, health_status,
                     created_at, updated_at)
                VALUES (?, ?, 'Destination', 'DOCUMENT_STORE_DELIVERY', 'OUTBOUND', 'HTTP_API', 'NONE', 5, 0,
                        'SUBMISSION_ACK', true, 1, 'UNKNOWN', now(), now())
                """, f.destination.id.value(), f.tenant.value());
    }

    // ------------------------------------------------------------------------------------------------ the switch

    @Test
    void switchedBackToInlineMidRunEveryQueuedExportGoesOutOnceAndANewCaseGoesInline() throws Exception {
        DocumentPackage sent = queued();
        step();
        DocumentPackage forYou = queued();
        f.destination.failWith = LvErrorCode.DLV_004;
        step();
        DocumentPackage forIt = queued();
        f.destination.failWith = LvErrorCode.DLV_005;
        step();
        assertTrue(f.service.setDestinationWorking(f.tenant, f.destination.id, ActorId.generate(),
                CorrelationId.generate()));
        DocumentPackage onSchedule = queued();
        f.destination.failWith = LvErrorCode.DLV_007;
        step();
        DocumentPackage serverStopped = queued();
        assertEquals(serverStopped.id(), f.service.claimNext("server-a/stopped").orElseThrow().packageId());
        jdbc.update("UPDATE pipeline_jobs SET claim_until = clock_timestamp() - interval '1 second'"
                + " WHERE package_id = ?", serverStopped.id().value());
        DocumentPackage paused = queued();
        f.destination.down();
        step();
        DocumentPackage waitingBehindIt = queued();
        DocumentPackage withSplits = queued();
        DocumentPackage stagedCase = f.finishedCase(); // its last gate runs after the switch
        row(stagedCase);

        assertEquals(PipelineJobStatus.DONE, job(sent).status());
        assertEquals(PipelineJobStatus.WAITING_FOR_YOU, job(forYou).status());
        assertEquals(PipelineJobStatus.NEEDS_IT, job(forIt).status());
        assertEquals(PipelineJobStatus.WAITING_RETRY, job(onSchedule).status());
        assertEquals(PipelineJobStatus.WAITING_RETRY, job(paused).status());
        assertEquals(DestinationState.PAUSED, states.find(f.tenant, f.destination.id).orElseThrow().state());

        // The switch: this server restarts with export-mode inline, the destination still down.
        UUID split = UUID.randomUUID();
        splitsOf(withSplits, split);
        ExportJobWorkers workers = new ExportJobWorkers(inlineService, 100, "test");
        workers.start();
        try {
            await(() -> job(serverStopped).status() == PipelineJobStatus.READY
                    && job(serverStopped).claimExpiries() == 1, "the stopped server's claim is released");
            Thread.sleep(1500);
            assertEquals(1, f.destination.acceptedCalls.size(), "nothing goes out to a paused destination");
            assertEquals(ExportJobService.CaseExportState.DESTINATION_PAUSED,
                    inlineService.caseExportState(f.tenant, paused.id()), "the case still shows its export");

            // A staged case finishes now: its gate asks for a job, also with export-mode inline.
            stageJob(stagedCase);
            inlineQueue.queueCaseExport(f.tenant, stagedCase.id(), 1, CorrelationId.generate());

            f.destination.up();
            for (DocumentPackage pkg : List.of(serverStopped, paused, waitingBehindIt, withSplits, stagedCase)) {
                await(() -> done(pkg), "the queued export goes out after the probe resumes the destination");
            }
            // Its retry comes due.
            jdbc.update("UPDATE pipeline_jobs SET next_attempt_at = clock_timestamp() WHERE package_id = ?",
                    onSchedule.id().value());
            await(() -> done(onSchedule), "the case on its own schedule");

            // People: one resends, IT retries.
            assertEquals("DONE", inlineService.resend(f.tenant, List.of(job(forYou).id()), ActorId.generate(),
                    CorrelationId.generate()).get(0).status());
            assertTrue(inlineService.itRetry(f.tenant, List.of(job(forIt).id()), true, ActorId.generate(),
                    CorrelationId.generate()).get(0).saved());
            await(() -> done(forIt), "the IT retry");
            await(() -> jobs.findByTenantIdAndPackageId(f.tenant, withSplits.id()).stream().anyMatch(j ->
                    j.stage() == PipelineStage.SPLIT_EXPORT && j.status() == PipelineJobStatus.DONE), "its split");

            // A new case: inline, with no job.
            DocumentPackage later = f.finishedCase();
            row(later);
            trigger(later);
            assertEquals(PackageStatus.COMPLETED, later.status());
            assertEquals(List.of(), jobs.findByTenantIdAndPackageId(f.tenant, later.id()));
            assertTrue(inlineQueue.splitsGoInline(f.tenant, later.id(), 1));
        } finally {
            workers.stop();
        }
        assertFalse(inlineService.hasOpenJobs());
        assertEquals(10, f.destination.acceptedCalls.size(), "9 queued exports and the new case");
        assertEquals(10, new HashSet<>(f.destination.acceptedCalls).size(), "nothing went out twice");
        assertEquals(9, f.destination.acceptedFromJob.stream().filter(fromJob -> fromJob).count(),
                "the job's attempts are marked as such (one try per call), the new case's is not");
        assertFalse(f.destination.acceptedFromJob.get(9));
        for (DocumentPackage pkg : List.of(sent, forYou, forIt, onSchedule, serverStopped, paused, waitingBehindIt,
                withSplits, stagedCase)) {
            assertEquals(PackageStatus.COMPLETED, pkg.status());
            assertEquals(ExportJobService.CaseExportState.EXPORTED, inlineService.caseExportState(f.tenant, pkg.id()));
        }
        verify(f.derived, times(1)).autoSend(eq(f.tenant), eq(split), any());
    }

    /** Resend all and the IT retry queue work again for workers that were not running. */
    @Test
    void aJobAPersonSendsAgainLaterStartsTheWorkers() throws Exception {
        DocumentPackage forYou = queued();
        f.destination.failWith = LvErrorCode.DLV_004;
        step();
        f.destination.up();
        ExportJobWorkers workers = new ExportJobWorkers(inlineService, 100, "test");
        workers.start();
        try {
            Thread.sleep(500);
            assertEquals(PipelineJobStatus.WAITING_FOR_YOU, job(forYou).status(), "it waits for a person, as before");
            assertEquals(1, inlineService.resendAll(f.tenant, f.destination.id, ActorId.generate(),
                    CorrelationId.generate()).size());
            await(() -> done(forYou), "resend all");
        } finally {
            workers.stop();
        }
        assertEquals(1, f.destination.acceptedCalls.size());
    }

    @Test
    void withNoOpenExportJobNothingStartsUntilThereIsOne() throws Exception {
        DocumentPackage sent = queued();
        step();
        assertEquals(PipelineJobStatus.DONE, job(sent).status());
        ExportJobWorkers workers = new ExportJobWorkers(inlineService, 100, "test");
        workers.start();
        try {
            Thread.sleep(1500);
            assertFalse(workersRun(), "finished jobs start nothing");
            // A server still on job mode queues one more.
            DocumentPackage late = queued();
            await(() -> done(late), "found at the next look");
            assertTrue(workersRun());
        } finally {
            workers.stop();
        }
        await(() -> !workersRun(), "stopped");
    }

    // ---------------------------------------------------------------------------- never both inline and by a job

    @Test
    void aCaseWithAnOpenExportJobIsNeverExportedInline() {
        for (PipelineJobStatus status : PipelineJobStatus.values()) {
            if (!status.open() || status == PipelineJobStatus.PAUSED) continue;
            DocumentPackage pkg = queued();
            set(pkg, status);
            trigger(pkg); // final approval release, recovery scan, a person's trigger: all end here
            assertEquals(PackageStatus.PROCESSING, pkg.status(), status.name());
            assertEquals(0, f.destination.acceptedCalls.size(), status.name());
            set(pkg, PipelineJobStatus.STOPPED);
        }
        // This run's job is done: it is never sent again from here.
        DocumentPackage done = queued();
        set(done, PipelineJobStatus.DONE);
        trigger(done);
        assertEquals(0, f.destination.acceptedCalls.size());

        // A stopped job sends nothing any more: the case goes inline, once.
        DocumentPackage stopped = queued();
        set(stopped, PipelineJobStatus.STOPPED);
        trigger(stopped);
        assertEquals(PackageStatus.COMPLETED, stopped.status());
        assertEquals(1, f.destination.acceptedCalls.size());
    }

    @Test
    void aTriggerWhileTheJobIsSendingOrHasJustSentSendsNothing() {
        DocumentPackage pkg = queued();
        int[] triggers = {0};
        f.destination.duringCall = () -> {
            f.destination.duringCall = () -> { };
            triggers[0]++;
            trigger(pkg); // the job is RUNNING and sending
        };
        doAnswer(i -> {
            triggers[0]++;
            trigger(pkg); // sent and recorded (the next run number is 2), the case not completed yet
            return null;
        }).when(f.routing).processPackage(eq(pkg.id()), eq(f.tenant), anyInt(), any());

        step();

        assertEquals(2, triggers[0]);
        assertEquals(1, f.destination.acceptedCalls.size());
        assertEquals(PackageStatus.COMPLETED, pkg.status());
        assertEquals(PipelineJobStatus.DONE, job(pkg).status());
    }

    @Test
    void aRepeatedRunOfAStagedCaseGetsItsOwnJobAndAnInlineOneExportsInline() {
        DocumentPackage pkg = queued();
        step();
        assertFalse(inlineQueue.jobHasCase(f.tenant, pkg.id(), 2), "the first run's job is done; the second is new");
        assertThrows(IllegalStateException.class, () -> inlineQueue.queueCaseExport(f.tenant, pkg.id(), 2,
                CorrelationId.generate()), "not a staged case: refused while export jobs are off");
        stageJob(pkg);
        inlineQueue.queueCaseExport(f.tenant, pkg.id(), 2, CorrelationId.generate());
        assertTrue(inlineQueue.jobHasCase(f.tenant, pkg.id(), 2));
        assertFalse(inlineQueue.splitsGoInline(f.tenant, pkg.id(), 2));
        assertThrows(IllegalStateException.class, () -> inlineQueue.queueSplitExport(f.tenant, pkg.id(), null,
                UUID.randomUUID(), f.destination.id, 3, CorrelationId.generate()), "no job exports that run");
    }

    // -------------------------------------------------------------------------------------------------- helpers

    private DocumentPackage queued() {
        DocumentPackage pkg = f.finishedCase();
        row(pkg);
        f.queue.queueCaseExport(f.tenant, pkg.id(), 1, CorrelationId.generate());
        return pkg;
    }

    private void row(DocumentPackage pkg) {
        jdbc.update("INSERT INTO document_packages (id, tenant_id, package_type, status, created_by, created_at,"
                + " updated_at) VALUES (?, ?, 'MANUAL_UPLOAD', 'PROCESSING', ?, now(), now())", pkg.id().value(),
                f.tenant.value(), UUID.randomUUID());
    }

    /** One run of the server still on job mode. */
    private void step() {
        assertTrue(f.step("server-a/worker-1").isPresent());
    }

    private void stageJob(DocumentPackage pkg) {
        DocumentId document = f.documents.get(pkg.id()).get(0).id();
        assertTrue(jobs.enqueue(PipelineJob.ready(f.tenant, PipelineStage.REDACT, pkg.id(), document, null, null, 1,
                java.time.Instant.now())));
        jdbc.update("UPDATE pipeline_jobs SET status = 'DONE' WHERE package_id = ? AND stage = 'REDACT'",
                pkg.id().value());
    }

    /** What derived routing does: it asks whether this run's splits go inline, and queues them if not. */
    private void splitsOf(DocumentPackage pkg, UUID split) {
        DerivedDelivery delivery = mock(DerivedDelivery.class);
        when(delivery.id()).thenReturn(split);
        when(delivery.status()).thenReturn(DerivedDeliveryStatus.PENDING);
        when(f.derived.get(f.tenant, split)).thenReturn(delivery);
        doAnswer(i -> {
            assertFalse(inlineQueue.splitsGoInline(f.tenant, pkg.id(), 1));
            inlineQueue.queueSplitExport(f.tenant, pkg.id(), f.documents.get(pkg.id()).get(0).id(), split,
                    IntegrationConnectionId.generate(), 1, i.getArgument(3));
            return null;
        }).when(f.routing).processPackage(eq(pkg.id()), eq(f.tenant), eq(1), any());
    }

    private PipelineJob job(DocumentPackage pkg) {
        return jobs.findByTenantIdAndPackageId(f.tenant, pkg.id()).stream()
                .filter(j -> j.stage() == PipelineStage.EXPORT).findFirst().orElseThrow();
    }

    private boolean done(DocumentPackage pkg) {
        return job(pkg).status() == PipelineJobStatus.DONE;
    }

    private void set(DocumentPackage pkg, PipelineJobStatus status) {
        jdbc.update("UPDATE pipeline_jobs SET status = ? WHERE package_id = ?", status.name(), pkg.id().value());
    }

    /** The inline trigger of a server with export-mode inline. */
    private void trigger(DocumentPackage pkg) {
        PackageId id = pkg.id();
        new TriggerPackageProcessingService(f.packageRepository, f.documentRepository, mock(FindingRepository.class),
                mock(ArtifactRepository.class), mock(ProcessingLockPort.class), mock(StartPackageProcessingUseCase.class),
                mock(StartDocumentProcessingUseCase.class), mock(ExtractDocumentTextUseCase.class),
                mock(DetectSensitiveDataUseCase.class), mock(RedactDocumentUseCase.class), f.export, f.routing,
                new SelectiveRetryExecutor(1, Duration.ZERO), f.lifecycle,
                mock(DocumentIntelligenceExecutionService.class), f.audit, f.incidents,
                mock(ProductTelemetryService.class), inlineQueue)
                .execute(TriggerPackageProcessingCommand.of(id, f.tenant, ActorId.generate(), CorrelationId.generate()));
    }

    private static boolean workersRun() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.isAlive() && t.getName().matches("export-worker-\\d+"));
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long until = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < until, "not in time: " + what);
            Thread.sleep(50);
        }
    }
}
