package com.securedoc.platform.application.processing.service;

import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.licensing.TenantLicensingService;
import com.securedoc.platform.application.pipeline.ExportJobScheduler;
import com.securedoc.platform.application.pipeline.PageBatch;
import com.securedoc.platform.application.pipeline.PageBatchCommand;
import com.securedoc.platform.application.pipeline.PipelineMode;
import com.securedoc.platform.domain.business.PageBatchSettings;
import com.securedoc.platform.application.pipeline.StagedProcessingSettings;
import com.securedoc.platform.application.pipeline.StagedStepScope;
import com.securedoc.platform.application.port.in.DetectSensitiveDataUseCase;
import com.securedoc.platform.application.port.in.ExportPackageDocumentsUseCase;
import com.securedoc.platform.application.port.in.ExtractDocumentTextUseCase;
import com.securedoc.platform.application.port.in.ProcessDerivedRoutingUseCase;
import com.securedoc.platform.application.port.in.RedactDocumentUseCase;
import com.securedoc.platform.application.port.in.StartDocumentProcessingUseCase;
import com.securedoc.platform.application.port.in.StartPackageProcessingUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.ProcessingLockPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TransactionPort;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.processing.result.ProcessingWorkItem;
import com.securedoc.platform.application.processing.result.StartDocumentProcessingResult;
import com.securedoc.platform.application.processing.result.StartPackageProcessingResult;
import com.securedoc.platform.application.intelligence.service.DocumentIntelligenceExecutionService;
import com.securedoc.platform.application.resilience.SelectiveRetryExecutor;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.technical.service.ProcessingIncidentService;
import com.securedoc.platform.application.telemetry.ProductTelemetryService;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.document.Page;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.vo.Confidence;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineJobStatus;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import com.securedoc.platform.domain.tenant.Tenant;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import com.securedoc.platform.infrastructure.persistence.adapter.JpaDocumentRepositoryAdapter;
import com.securedoc.platform.infrastructure.persistence.adapter.JpaFindingRepositoryAdapter;
import com.securedoc.platform.infrastructure.persistence.adapter.JpaPackageRepositoryAdapter;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataDocumentPackageRepository;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataDocumentRepository;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataFindingRepository;
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
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 0048: the staged pipeline on real PostgreSQL, with the real job queue, the real JPA repositories, the real
 * inline gate ({@link TriggerPackageProcessingService#completePackageProcessingIfReady}) and the real LV-PRC-008 stop.
 * Only the step use cases are stand-ins; they make the same document state changes as the real ones (text read:
 * UPLOADED → EXTRACTING → DETECTING; found: → REDACTING; redacted: → PROCESSED), so what is tested is the scheduling:
 * jobs, gates, fencing and claims that run out. Its own database on the throwaway server named by
 * {@code LEXAVAULT_TEST_PG_URL}, dropped afterwards. Synthetic data only.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class StagedPipelinePostgresTest {
    private static String adminUrl;
    private static String user;
    private static String password;
    private static String dbName;
    private static String url;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static JpaPackageRepositoryAdapter packages;
    private static JpaDocumentRepositoryAdapter documents;
    private static JpaFindingRepositoryAdapter findings;
    private static JdbcPipelineJobRepository jobs;
    private static TransactionPort transactions;
    private static Tenant tenant;

    private final List<AuditRecord> audits = new CopyOnWriteArrayList<>();
    private final List<ArtifactStorageReference> deleted = new CopyOnWriteArrayList<>();
    private final ExportJobScheduler exports = mock(ExportJobScheduler.class);
    private final PackageLifecycleService lifecycle = mock(PackageLifecycleService.class);
    private final ProcessingIncidentService incidents = mock(ProcessingIncidentService.class);
    private final ProductTelemetryService telemetry = mock(ProductTelemetryService.class);
    /** What the stand-in FIND step does besides moving the document on (a finding, a failure). */
    private Consumer<Document> onFind = document -> { };
    /** What the stand-in TEXT step does after saving its result (the fencing test takes the job away here). */
    private Consumer<Document> afterTextSaved = document -> { };
    /** Task 0049: the pages of a document, when large documents are split; 0 means one job per document. */
    private int pages = 0;
    private PageBatchSettings batchSettings = PageBatchSettings.DEFAULTS;
    /** Task 0052: one AI request may cover several pages (then Find batches share pages). */
    private boolean severalPagesPerAiRequest = true;
    private final ExportPackageDocumentsUseCase inlineExport = mock(ExportPackageDocumentsUseCase.class);
    /** Task 0052: where a finished case is handed to export; a test may use the real queue instead of the stand-in. */
    private ExportJobScheduler scheduler = exports;
    private final List<String> batchesRun = new CopyOnWriteArrayList<>();
    private final List<String> gathered = new CopyOnWriteArrayList<>();
    /** Runs inside a page batch's step, after its result is kept. */
    private Consumer<PageBatchCommand> inBatch = command -> { };
    private StagedProcessingService service;
    private java.util.function.Function<StagedProcessingSettings, StagedProcessingService> serviceWith;
    private PackageProcessingRecoveryService recovery;

    @BeforeAll
    static void start() throws Exception {
        adminUrl = System.getenv("LEXAVAULT_TEST_PG_URL");
        user = System.getenv("LEXAVAULT_TEST_PG_USER");
        password = System.getenv("LEXAVAULT_TEST_PG_PASSWORD");
        dbName = "lv_t_staged_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); Statement s = admin.createStatement()) {
            s.execute("CREATE DATABASE " + dbName);
        }
        url = adminUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + dbName + "$1");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            new Liquibase("db/changelog/db.changelog-master.xml", new ClassLoaderResourceAccessor(),
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)))
                    .update(new Contexts());
        }
        JpaSetup.url = url;
        context = new AnnotationConfigApplicationContext(JpaSetup.class);
        DataSource dataSource = context.getBean(DataSource.class);
        PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(manager);
        TransactionTemplate requiresNew = new TransactionTemplate(manager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions = new TransactionPort() {
            @Override
            public <T> T inNewTransaction(java.util.function.Supplier<T> work) {
                return requiresNew.execute(status -> work.get());
            }
        };
        documents = new JpaDocumentRepositoryAdapter(context.getBean(SpringDataDocumentRepository.class));
        packages = new JpaPackageRepositoryAdapter(context.getBean(SpringDataDocumentPackageRepository.class),
                context.getBean(SpringDataDocumentRepository.class));
        findings = new JpaFindingRepositoryAdapter(context.getBean(SpringDataFindingRepository.class));
        jobs = new JdbcPipelineJobRepository(new NamedParameterJdbcTemplate(dataSource), manager);
        tenant = Tenant.create("Staged pipeline tenant", "staged-" + UUID.randomUUID().toString().substring(0, 8));
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO tenants (id, name, slug, status, created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
                tenant.id().value(), tenant.name(), tenant.slug(), now, now);
    }

    @AfterAll
    static void stop() throws Exception {
        if (context != null) context.close();
        if (dbName != null) {
            try (Connection admin = DriverManager.getConnection(adminUrl, user, password); Statement s = admin.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
            }
        }
    }

    @BeforeEach
    void wire() {
        // One queue for the whole class: every test starts with no job left open by an earlier one.
        jdbc.update("UPDATE pipeline_jobs SET status = 'STOPPED', claim_owner = NULL, claim_until = NULL"
                + " WHERE status NOT IN ('DONE', 'STOPPED')");
        when(exports.jobMode()).thenReturn(true);
        when(lifecycle.nextExportCycle(any(), any())).thenReturn(1);
        when(lifecycle.requiresFinalApproval(any(), any())).thenReturn(false);
        AuditService audit = new AuditService(audits::add);

        StartPackageProcessingUseCase startPackage = command -> tx.execute(s -> {
            DocumentPackage pkg = packages.findByIdAndTenantId(command.packageId(), command.tenantId()).orElseThrow();
            pkg.startProcessing();
            packages.save(pkg);
            List<ProcessingWorkItem> items = documents.findByPackageIdAndTenantId(pkg.id(), pkg.tenantId()).stream()
                    .map(d -> ProcessingWorkItem.of(d.id(), pkg.id(), pkg.tenantId(), d.status())).toList();
            return StartPackageProcessingResult.of(pkg.id(), pkg.status(), items);
        });
        StartDocumentProcessingUseCase startDocument = mock(StartDocumentProcessingUseCase.class);
        when(startDocument.execute(any(com.securedoc.platform.application.processing.command.StartDocumentProcessingCommand.class)))
                .thenAnswer(i -> {
                    var command = (com.securedoc.platform.application.processing.command.StartDocumentProcessingCommand) i.getArgument(0);
                    Document d = documents.findByIdAndTenantId(command.documentId(), command.tenantId()).orElseThrow();
                    d.startExtraction();
                    documents.save(d);
                    return StartDocumentProcessingResult.of(d.id(), d.packageId(), d.tenantId(), d.status(), "k", "t");
                });
        StoragePort storage = mock(StoragePort.class);
        when(storage.storeForPackage(any(), any(), anyString(), any(), anyLong(), anyString())).thenAnswer(i -> {
            ArtifactStorageReference ref = ArtifactStorageReference.local("t/p/d/EXTRACTED_TEXT/" + UUID.randomUUID());
            StagedStepScope.recordStored(ref);
            return ref;
        });
        org.mockito.Mockito.doAnswer(i -> deleted.add(i.getArgument(0))).when(storage).delete(any());
        ExtractDocumentTextUseCase extract = new ExtractDocumentTextUseCase() {
            @Override
            public com.securedoc.platform.application.extraction.result.ExtractDocumentTextResult execute(
                    com.securedoc.platform.application.extraction.command.ExtractDocumentTextCommand command) {
                storage.storeForPackage(command.tenantId(), command.packageId(), "x", null, 1, "text/plain");
                Document d = documents.findByIdAndTenantId(command.documentId(), command.tenantId()).orElseThrow();
                d.completeExtraction(java.util.stream.IntStream.rangeClosed(1, Math.max(1, pages))
                        .mapToObj(n -> Page.of(n, 612, 792)).toList());
                documents.save(d);
                afterTextSaved.accept(d);
                return null;
            }

            @Override
            public void extractBatch(PageBatchCommand command) {
                storage.storeForPackage(command.tenantId(), command.packageId(), "x", null, 1, "text/plain");
                batchesRun.add("TEXT " + command.batch().from() + "-" + command.batch().to());
                inBatch.accept(command);
            }

            @Override
            public com.securedoc.platform.application.extraction.result.ExtractDocumentTextResult completeFromBatches(
                    com.securedoc.platform.application.extraction.command.ExtractDocumentTextCommand command,
                    List<PageBatch> batches) {
                gathered.add("TEXT " + batches.stream().map(b -> b.from() + "-" + b.to()).toList());
                return execute(command);
            }
        };
        DetectSensitiveDataUseCase detect = new DetectSensitiveDataUseCase() {
            @Override
            public com.securedoc.platform.application.detection.result.DetectSensitiveDataResult execute(
                    com.securedoc.platform.application.detection.command.DetectSensitiveDataCommand command) {
                Document d = documents.findByIdAndTenantId(command.documentId(), command.tenantId()).orElseThrow();
                onFind.accept(d);
                d.startRedaction();
                documents.save(d);
                return null;
            }

            @Override
            public void detectBatch(PageBatchCommand command) {
                batchesRun.add("FIND " + command.batch().from() + "-" + command.batch().to());
                inBatch.accept(command);
            }

            @Override
            public com.securedoc.platform.application.detection.result.DetectSensitiveDataResult completeFromBatches(
                    com.securedoc.platform.application.detection.command.DetectSensitiveDataCommand command,
                    List<PageBatch> batches) {
                gathered.add("FIND " + batches.stream().map(b -> b.from() + "-" + b.to()).toList());
                return execute(command);
            }
        };
        RedactDocumentUseCase redact = command -> {
            Document d = documents.findByIdAndTenantId(command.documentId(), command.tenantId()).orElseThrow();
            d.completeProcessing();
            documents.save(d);
            // As the real redaction: the final check is audited (ids only).
            audit.recordSuccess(command.tenantId(), ActorId.generate(), AuditAction.DOCUMENT_REDACTION_VERIFIED,
                    "Document", d.id().toString(), d.packageId(), command.correlationId(), "test final check");
            return null;
        };
        TriggerPackageProcessingService inline = new TriggerPackageProcessingService(packages, documents, findings,
                mock(ArtifactRepository.class), mock(ProcessingLockPort.class), startPackage, startDocument, extract,
                detect, redact, inlineExport, mock(ProcessDerivedRoutingUseCase.class),
                mock(SelectiveRetryExecutor.class), lifecycle, mock(DocumentIntelligenceExecutionService.class), audit,
                incidents, mock(TenantLicensingService.class), telemetry, scheduler);
        recovery = new PackageProcessingRecoveryService(packages, lifecycle, incidents, audit, telemetry);
        StagedProcessingSettings settings = new StagedProcessingSettings(PipelineMode.STAGED, Duration.ofSeconds(30),
                3, 1, 1, 1, Duration.ofMillis(200));
        serviceWith = chosen -> new StagedProcessingService(chosen, jobs, packages, documents, mock(ProcessingLockPort.class),
                startPackage, startDocument, extract, detect, redact, lifecycle, incidents, recovery, audit,
                mock(TenantLicensingService.class), telemetry, inline, transactions, storage,
                new StagedProcessingService.PageBatching(t -> batchSettings,
                        d -> pages == 0 ? OptionalInt.empty() : OptionalInt.of(pages),
                        t -> severalPagesPerAiRequest));
        service = serviceWith.apply(settings);
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    void eachDocumentRunsTextFindRedactAndTheCaseWaitsAtEachGate() {
        DocumentPackage pkg = submittedCase(3);
        service.plan(trigger(pkg));
        assertEquals(PackageStatus.PROCESSING, status(pkg));
        assertEquals(3, count(pkg, PipelineStage.TEXT), "one TEXT job per document");

        runAll(PipelineStage.TEXT);
        assertEquals(3, count(pkg, PipelineStage.FIND), "each read document gets its FIND job");
        assertEquals(0, count(pkg, PipelineStage.REDACT));

        runOne(PipelineStage.FIND);
        runOne(PipelineStage.FIND);
        assertEquals(0, count(pkg, PipelineStage.REDACT), "the gate waits for the last document");
        runOne(PipelineStage.FIND);
        assertEquals(3, count(pkg, PipelineStage.REDACT), "all found and nothing for a person: every document Redact");
        verify(exports, never()).queueCaseExport(any(), any(), anyInt(), any());

        runOne(PipelineStage.REDACT);
        runOne(PipelineStage.REDACT);
        verify(exports, never()).queueCaseExport(any(), any(), anyInt(), any());
        runOne(PipelineStage.REDACT);
        verify(exports, times(1)).queueCaseExport(eq(pkg.tenantId()), eq(pkg.id()), eq(1), any());
        documents.findByPackageIdAndTenantId(pkg.id(), pkg.tenantId())
                .forEach(d -> assertEquals(DocumentStatus.PROCESSED, d.status()));
        assertTrue(jobsOf(pkg).stream().allMatch(j -> j.status() == PipelineJobStatus.DONE));
        assertEquals(3, audits.stream().filter(a -> a.action() == AuditAction.DOCUMENT_REDACTION_VERIFIED).count(),
                "the final check ran once on every redacted document");
        assertEquals(9, audits.stream().filter(a -> a.action() == AuditAction.PIPELINE_JOB_QUEUED).count());
        assertTrue(audits.stream().filter(a -> a.action() == AuditAction.PIPELINE_JOB_STATUS_CHANGED).count() >= 9,
                "every job's status change is audited");

        // The gate again (a late trigger, a second server) adds no job. The case stays PROCESSING (Ready for export)
        // while its export runs, so the gate asks again for the same run; the export queue adds nothing for a run it
        // already has (ExportJobQueue returns before queuing or auditing).
        service.gate(pkg.tenantId(), pkg.id(), CorrelationId.generate());
        verify(exports, times(2)).queueCaseExport(eq(pkg.tenantId()), eq(pkg.id()), eq(1), any());
        assertEquals(9, jobsOf(pkg).size());
    }

    @Test
    void aFindingForAPersonSendsTheCaseToReviewAndTheReviewersContinueQueuesRedact() {
        DocumentPackage pkg = submittedCase(2);
        List<Finding> forReview = new ArrayList<>();
        onFind = document -> {
            if (forReview.isEmpty()) {
                Finding f = Finding.create(document.tenantId(), document.id(), EntityType.SSN,
                        FindingLocation.textOnly(1, 0, 11), Confidence.of(0.5), DetectorSource.of("test"));
                tx.executeWithoutResult(s -> findings.save(f));
                forReview.add(f);
            }
        };
        service.plan(trigger(pkg));
        runAll(PipelineStage.TEXT);
        runAll(PipelineStage.FIND);
        assertEquals(PackageStatus.PENDING_REVIEW, status(pkg), "as today: a finding for a person");
        assertEquals(0, count(pkg, PipelineStage.REDACT), "nothing is redacted before the review");

        Finding reviewed = findings.findByDocumentIdAndTenantId(forReview.get(0).documentId(), pkg.tenantId()).get(0);
        reviewed.confirm(ActorId.generate(), "reviewed");
        tx.executeWithoutResult(s -> findings.save(reviewed));
        service.plan(trigger(pkg));
        assertEquals(2, count(pkg, PipelineStage.REDACT), "the reviewer's continue queues every document");
        runAll(PipelineStage.REDACT);
        verify(exports, times(1)).queueCaseExport(eq(pkg.tenantId()), eq(pkg.id()), eq(1), any());
    }

    @Test
    void aFailingStepFailsItsDocumentAndTheGateFailsTheCaseAsInline() {
        DocumentPackage pkg = submittedCase(2);
        List<DocumentId> failOnce = new ArrayList<>();
        onFind = document -> {
            if (failOnce.isEmpty()) {
                failOnce.add(document.id());
                throw new IllegalStateException("synthetic detection failure");
            }
        };
        service.plan(trigger(pkg));
        runAll(PipelineStage.TEXT);
        runAll(PipelineStage.FIND);
        assertEquals(DocumentStatus.FAILED, documents.findByIdAndTenantId(failOnce.get(0), pkg.tenantId())
                .orElseThrow().status());
        assertEquals(PackageStatus.FAILED, status(pkg), "a failed document fails the case, as inline");
        verify(incidents, times(1)).recordFailure(any(), any(), eq(failOnce.get(0)), any(), any(), any(), any(), any());
        PipelineJob failed = jobsOf(pkg).stream().filter(j -> j.stage() == PipelineStage.FIND
                && j.documentId().equals(failOnce.get(0))).findFirst().orElseThrow();
        assertEquals(PipelineJobStatus.DONE, failed.status());
        assertTrue(failed.lastLvCode() != null && failed.lastLvCode().startsWith("LV-"), "the job keeps its code");
        assertEquals(0, count(pkg, PipelineStage.REDACT));
        verify(exports, never()).queueCaseExport(any(), any(), anyInt(), any());
    }

    /** #342: a worker that lost its claim (paused, cut off) saves nothing, and its stored file is deleted. */
    @Test
    void aLateSaveFromAWorkerThatLostItsClaimIsRefusedAndNothingOfTheStepCounts() {
        DocumentPackage pkg = submittedCase(1);
        service.plan(trigger(pkg));
        PipelineJob mine = service.claimNext(PipelineStage.TEXT, "server-a/text-worker-1").orElseThrow();
        // While this worker works, its claim runs out and another worker takes the job (a new claim number). Done from
        // another connection, as another server would.
        JdbcTemplate otherServer = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        afterTextSaved = document -> otherServer.update("UPDATE pipeline_jobs SET claim_token = claim_token + 1,"
                + " claim_owner = 'server-b/text-worker-1' WHERE id = ?", mine.id());

        assertEquals(StagedProcessingService.Outcome.CLAIM_LOST, service.run(mine));

        Document document = documents.findByPackageIdAndTenantId(pkg.id(), pkg.tenantId()).get(0);
        assertEquals(DocumentStatus.UPLOADED, document.status(), "the step's own writes were rolled back");
        assertEquals(0, count(pkg, PipelineStage.FIND), "no next job from a refused result");
        PipelineJob now = jobs.findByTenantIdAndId(pkg.tenantId(), mine.id()).orElseThrow();
        assertEquals(PipelineJobStatus.RUNNING, now.status(), "the newer worker's claim is untouched");
        assertEquals(mine.claimToken() + 1, now.claimToken());
        assertEquals(1, deleted.size(), "the file the refused step stored is deleted");
        assertFalse(jobs.finish(mine.id(), mine.claimToken(), new com.securedoc.platform.application.port.out
                .PipelineJobRepository.Finish(PipelineJobStatus.DONE, 0, null, null, null, false, false, false)),
                "a stale claim number never saves");
    }

    /** A server stopped mid-job: the claim runs out, the job is queued again (one attempt); after 3, LV-PRC-008. */
    @Test
    void claimsThatRunOutRequeueTheJobAndAfterThreeTheCaseStopsWithPrc008() {
        DocumentPackage pkg = submittedCase(1);
        service.plan(trigger(pkg));
        for (int attempt = 1; attempt <= 3; attempt++) {
            PipelineJob claimed = service.claimNext(PipelineStage.TEXT, "server-a/text-worker-1").orElseThrow();
            jdbc.update("UPDATE pipeline_jobs SET claim_until = clock_timestamp() - interval '1 second' WHERE id = ?",
                    claimed.id());
            assertEquals(1, service.releaseExpiredClaims());
            PipelineJob after = jobs.findByTenantIdAndId(pkg.tenantId(), claimed.id()).orElseThrow();
            assertEquals(attempt, after.attempts(), "a lost run counts as an attempt");
            assertEquals(attempt < 3 ? PipelineJobStatus.READY : PipelineJobStatus.NEEDS_IT, after.status());
        }
        assertEquals("LV-PRC-008", jobsOf(pkg).get(0).lastLvCode());
        assertEquals(PackageStatus.FAILED, status(pkg), "the case stops, as task 0023");
        assertTrue(audits.stream().anyMatch(a -> a.action() == AuditAction.PACKAGE_PROCESSING_RECOVERY_EXHAUSTED
                && a.detail().contains("LV-PRC-008")));
        assertEquals(Optional.empty(), service.claimNext(PipelineStage.TEXT, "server-b/text-worker-1"));
    }

    @Test
    void aStoppedCaseStopsItsRemainingJobs() {
        DocumentPackage pkg = submittedCase(2);
        service.plan(trigger(pkg));
        tx.executeWithoutResult(s -> {
            DocumentPackage loaded = packages.findByIdAndTenantId(pkg.id(), pkg.tenantId()).orElseThrow();
            loaded.cancel();
            packages.save(loaded);
        });
        runAll(PipelineStage.TEXT);
        assertTrue(jobsOf(pkg).stream().allMatch(j -> j.status() == PipelineJobStatus.STOPPED
                && "CASE_NOT_OPEN".equals(j.lastDetail())));
        documents.findByPackageIdAndTenantId(pkg.id(), pkg.tenantId())
                .forEach(d -> assertEquals(DocumentStatus.UPLOADED, d.status()));
    }

    /** Item 7: a case started inline before the switch stays inline; only new cases (and staged ones) are staged. */
    @Test
    void onlyNewCasesAndStagedCasesAreStaged() {
        DocumentPackage fresh = submittedCase(1);
        assertTrue(service.handles(trigger(fresh)), "a case not started yet is staged");
        DocumentPackage inlineCase = submittedCase(1);
        tx.executeWithoutResult(s -> {
            DocumentPackage loaded = packages.findByIdAndTenantId(inlineCase.id(), inlineCase.tenantId()).orElseThrow();
            loaded.startProcessing();
            packages.save(loaded);
        });
        assertFalse(service.handles(trigger(inlineCase)), "a case in the middle of an inline run finishes inline");
        service.plan(trigger(fresh));
        assertTrue(service.handles(trigger(fresh)), "a staged case stays staged");
    }

    // ------------------------------------------------------------------------------- page batches (task 0049)

    @Test
    void aLargeDocumentIsSplitIntoBatchesThatRunInParallelAndIsGatheredOnceBeforeRedact() throws Exception {
        pages = 500;
        DocumentPackage pkg = submittedCase(1);
        service.plan(trigger(pkg));
        assertEquals(25, count(pkg, PipelineStage.TEXT), "500 pages, 20 per batch");

        runWithWorkers(PipelineStage.TEXT, 4);
        assertEquals(25, batchesRun.size());
        assertEquals(1, gathered.size(), "one gathering, by the last batch");
        assertTrue(gathered.get(0).startsWith("TEXT [1-20, 21-40,") && gathered.get(0).endsWith("481-500]"));
        assertEquals(DocumentStatus.DETECTING, document(pkg).status());
        assertEquals(28, count(pkg, PipelineStage.FIND), "2 pages shared with the next batch");
        assertEquals(0, count(pkg, PipelineStage.REDACT));

        runWithWorkers(PipelineStage.FIND, 4);
        assertEquals(2, gathered.size());
        assertTrue(gathered.get(1).startsWith("FIND [1-20, 19-38,"));
        assertEquals(DocumentStatus.REDACTING, document(pkg).status());
        assertEquals(1, count(pkg, PipelineStage.REDACT), "Redact is one job for the document");
        assertTrue(jobsOf(pkg).stream().filter(j -> j.stage() != PipelineStage.REDACT)
                .allMatch(j -> j.status() == PipelineJobStatus.DONE && j.lastDetail() == null));
        long owners = audits.stream().filter(a -> a.action() == AuditAction.PIPELINE_JOB_STATUS_CHANGED
                && a.detail().contains("pages=")).count();
        assertEquals(53, owners, "every batch's status change is audited with its pages");
        runAll(PipelineStage.REDACT);
        verify(exports, times(1)).queueCaseExport(eq(pkg.tenantId()), eq(pkg.id()), eq(1), any());
    }

    @Test
    void twoLastBatchesFinishingAtTheSameMomentGatherOnce() throws Exception {
        pages = 40;
        for (int round = 0; round < 5; round++) {
            gathered.clear();
            DocumentPackage pkg = submittedCase(1);
            service.plan(trigger(pkg));
            java.util.concurrent.CyclicBarrier both = new java.util.concurrent.CyclicBarrier(2);
            inBatch = command -> {
                try {
                    both.await(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            runWithWorkers(PipelineStage.TEXT, 2);
            assertEquals(1, gathered.size(), "round " + round);
            assertEquals(DocumentStatus.DETECTING, document(pkg).status());
            inBatch = command -> { };
            runAll(PipelineStage.FIND);
            runAll(PipelineStage.REDACT);
        }
    }

    @Test
    void aDocumentUpToTheBatchSizeIsOneJob() {
        pages = 20;
        DocumentPackage pkg = submittedCase(1);
        service.plan(trigger(pkg));
        runAll(PipelineStage.TEXT);
        runAll(PipelineStage.FIND);
        assertEquals(List.of(), batchesRun);
        assertEquals(3, jobsOf(pkg).size());
        assertTrue(jobsOf(pkg).stream().noneMatch(PipelineJob::isPageBatch));
    }

    @Test
    void aBatchWhoseClaimWasLostSavesNothingAndItsRetryGathersTheDocument() {
        pages = 40;
        DocumentPackage pkg = submittedCase(1);
        service.plan(trigger(pkg));
        runOne(PipelineStage.TEXT);
        PipelineJob last = service.claimNext(PipelineStage.TEXT, "server-a/text-worker-1").orElseThrow();
        // Another server's write: its own connection, not the step's transaction.
        inBatch = command -> {
            try (Connection other = DriverManager.getConnection(url, user, password);
                 Statement s = other.createStatement()) {
                s.execute("UPDATE pipeline_jobs SET status = 'READY', claim_token = claim_token + 1,"
                        + " claim_owner = NULL, claim_until = NULL WHERE id = '" + command.batch().jobId() + "'");
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        };
        assertEquals(StagedProcessingService.Outcome.CLAIM_LOST, service.run(last));
        assertEquals(DocumentStatus.UPLOADED, document(pkg).status(), "the gathering of the late worker does not count");
        assertEquals(0, count(pkg, PipelineStage.FIND));
        assertFalse(deleted.isEmpty(), "the files it stored are deleted");
        inBatch = command -> { };
        runOne(PipelineStage.TEXT);
        assertEquals(DocumentStatus.DETECTING, document(pkg).status());
        assertEquals(3, count(pkg, PipelineStage.FIND), "40 pages: 1-20, 19-38, 37-40");
    }

    @Test
    void aBatchThatFailsFailsTheDocumentAndNothingIsGathered() {
        pages = 60;
        DocumentPackage pkg = submittedCase(1);
        service.plan(trigger(pkg));
        inBatch = command -> {
            if (command.batch().from() == 21) throw new IllegalStateException("test failure");
        };
        runAll(PipelineStage.TEXT);
        assertEquals(List.of(), gathered);
        assertEquals(DocumentStatus.FAILED, document(pkg).status());
        assertEquals(PackageStatus.FAILED, status(pkg));
        assertTrue(jobsOf(pkg).stream().noneMatch(j -> j.status() == PipelineJobStatus.READY
                || j.status() == PipelineJobStatus.RUNNING));
    }

    @Test
    void aTechnicalRetryInTheSameCaseRunStartsOverAlsoWithOtherBatchSettings() {
        pages = 60;
        DocumentPackage pkg = submittedCase(1);
        service.plan(trigger(pkg));
        inBatch = command -> {
            if (command.batch().from() == 21) throw new IllegalStateException("test failure");
        };
        runAll(PipelineStage.TEXT);
        assertEquals(PackageStatus.FAILED, status(pkg));
        tx.executeWithoutResult(s -> {
            Document d = document(pkg);
            d.prepareForTechnicalRetry();
            documents.save(d);
            DocumentPackage p = packages.findByIdAndTenantId(pkg.id(), pkg.tenantId()).orElseThrow();
            p.prepareForTechnicalRetry();
            packages.save(p);
        });
        inBatch = command -> { };
        batchSettings = new PageBatchSettings(30, 3);
        service.plan(trigger(pkg));
        assertEquals(2, count(pkg, PipelineStage.TEXT), "the retry's own batches: 1-30, 31-60");
        // Queuing again while it runs keeps the batches it was split into.
        batchSettings = new PageBatchSettings(10, 1);
        service.plan(trigger(pkg));
        assertEquals(2, count(pkg, PipelineStage.TEXT));
        runAll(PipelineStage.TEXT);
        runAll(PipelineStage.FIND);
        runAll(PipelineStage.REDACT);
        assertEquals(DocumentStatus.PROCESSED, document(pkg).status());
    }

    // --------------------------------------------------------------- switching back to inline (task 0049, step 4)

    @Test
    void switchedBackToInlineMidRunEveryStagedCaseStillFinishesAndNothingRunsTwice() throws Exception {
        pages = 60;
        List<DocumentPackage> cases = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            DocumentPackage pkg = submittedCase(2);
            service.plan(trigger(pkg));
            cases.add(pkg);
        }
        for (int i = 0; i < 5; i++) runOne(PipelineStage.TEXT); // mid-run
        // One job was running when that server stopped for the switch: its claim has run out.
        PipelineJob running = service.claimNext(PipelineStage.TEXT, "server-a/stopped").orElseThrow();
        jdbc.update("UPDATE pipeline_jobs SET claim_until = clock_timestamp() - interval '1 second' WHERE id = ?",
                running.id());
        DocumentPackage later = submittedCase(1);

        StagedProcessingService inlineService = serviceWith.apply(new StagedProcessingSettings(PipelineMode.INLINE,
                Duration.ofSeconds(30), 3, 2, 2, 2, Duration.ofMillis(100)));
        assertFalse(inlineService.handles(trigger(later)), "a new case goes inline");
        cases.forEach(pkg -> assertTrue(inlineService.handles(trigger(pkg)), "a staged case stays staged"));
        var workers = new com.securedoc.platform.api.processing.StageJobWorkers(inlineService, "test");
        workers.start();
        try {
            long until = System.currentTimeMillis() + 60_000;
            while ((inlineService.hasOpenJobs() || cases.stream().anyMatch(pkg -> documents
                    .findByPackageIdAndTenantId(pkg.id(), pkg.tenantId()).stream()
                    .anyMatch(d -> d.status() != DocumentStatus.PROCESSED))) && System.currentTimeMillis() < until) Thread.sleep(100);
        } finally {
            workers.stop();
        }
        for (DocumentPackage pkg : cases) {
            for (Document d : documents.findByPackageIdAndTenantId(pkg.id(), pkg.tenantId())) {
                assertEquals(DocumentStatus.PROCESSED, d.status());
            }
            assertTrue(jobsOf(pkg).stream().allMatch(j -> j.status() == PipelineJobStatus.DONE));
            verify(exports, org.mockito.Mockito.atLeastOnce()).queueCaseExport(eq(pkg.tenantId()), eq(pkg.id()), eq(1),
                    any());
        }
        // 6 documents: 3 TEXT and 4 FIND batches each (60 pages), one gathering per stage, one final check.
        assertEquals(6 * 3, batchesRun.stream().filter(b -> b.startsWith("TEXT")).count(), "no batch was read twice");
        assertEquals(6 * 4, batchesRun.stream().filter(b -> b.startsWith("FIND")).count());
        assertEquals(6 * 2, gathered.size());
        assertEquals(6, audits.stream().filter(a -> a.action() == AuditAction.DOCUMENT_REDACTION_VERIFIED).count());
        assertEquals(PackageStatus.SUBMITTED, status(later), "the stage workers do not touch the new case");
        assertEquals(0, jobsOf(later).size());
    }

    /** Task 0052: both modes back to inline. A staged case still ends in an export job; its gate sends nothing. */
    @Test
    void withExportModeInlineAStagedCaseStillEndsInOneExportJobAndNothingIsSentFromItsGate() throws Exception {
        var deliveryPort = mock(com.securedoc.platform.application.port.out.DeliveryPort.class);
        when(deliveryPort.describe(any())).thenReturn(
                new com.securedoc.platform.application.port.out.DeliveryPort.DeliveryDescriptor("LOCAL_FOLDER", null, null));
        scheduler = new com.securedoc.platform.application.pipeline.ExportJobQueue(jobs, deliveryPort,
                new AuditService(audits::add), com.securedoc.platform.application.pipeline.ExportJobSettings.defaults(
                com.securedoc.platform.application.pipeline.ExportMode.INLINE), java.time.Clock.systemUTC());
        wire();
        List<DocumentPackage> cases = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            DocumentPackage pkg = submittedCase(2);
            service.plan(trigger(pkg));
            cases.add(pkg);
        }
        runOne(PipelineStage.TEXT); // mid-run
        StagedProcessingService inlineService = serviceWith.apply(new StagedProcessingSettings(PipelineMode.INLINE,
                Duration.ofSeconds(30), 3, 2, 2, 2, Duration.ofMillis(100)));
        var workers = new com.securedoc.platform.api.processing.StageJobWorkers(inlineService, "test");
        workers.start();
        try {
            long until = System.currentTimeMillis() + 60_000;
            while (cases.stream().anyMatch(pkg -> count(pkg, PipelineStage.EXPORT) == 0)
                    && System.currentTimeMillis() < until) Thread.sleep(100);
        } finally {
            workers.stop();
        }
        for (DocumentPackage pkg : cases) {
            assertEquals(PackageStatus.PROCESSING, status(pkg), "Ready for export, as in job mode");
            // A late trigger (final approval, a person, a second server) runs the gate again: still one job.
            inlineService.plan(trigger(pkg));
            PipelineJob export = jobsOf(pkg).stream().filter(j -> j.stage() == PipelineStage.EXPORT)
                    .reduce((a, b) -> { throw new AssertionError("two export jobs"); }).orElseThrow();
            assertEquals(PipelineJobStatus.READY, export.status());
            assertTrue(scheduler.jobHasCase(pkg.tenantId(), pkg.id(), 1));
        }
        org.mockito.Mockito.verifyNoInteractions(inlineExport);
        assertTrue(jobs.hasOpenExportJobs());
        // A case that is not staged is never queued while export jobs are off.
        DocumentPackage notStaged = submittedCase(1);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> scheduler.queueCaseExport(
                notStaged.tenantId(), notStaged.id(), 1, CorrelationId.generate()));
        assertEquals(0, jobsOf(notStaged).size());
    }

    /** Task 0052: Find batches share pages only when one AI request may cover several pages. */
    @Test
    void withOnePagePerAiRequestNoPageIsInTwoFindBatches() {
        pages = 500;
        severalPagesPerAiRequest = false;
        DocumentPackage pkg = submittedCase(1);
        service.plan(trigger(pkg));
        runAll(PipelineStage.TEXT);
        List<PipelineJob> find = jobsOf(pkg).stream().filter(j -> j.stage() == PipelineStage.FIND)
                .sorted(java.util.Comparator.comparingInt(PipelineJob::pageFrom)).toList();
        assertEquals(25, find.size());
        for (int i = 0; i < find.size(); i++) {
            assertEquals(i * 20 + 1, find.get(i).pageFrom());
            assertEquals(i * 20 + 20, find.get(i).pageTo());
        }
        runAll(PipelineStage.FIND);
        assertEquals(1, gathered.stream().filter(g -> g.startsWith("FIND")).count());
        runAll(PipelineStage.REDACT);
        assertEquals(DocumentStatus.PROCESSED, document(pkg).status());

        severalPagesPerAiRequest = true;
        DocumentPackage shared = submittedCase(1);
        service.plan(trigger(shared));
        runAll(PipelineStage.TEXT);
        assertEquals(28, count(shared, PipelineStage.FIND), "several pages in one request: the stored overlap of 2");
        assertEquals(2, batchSettings.overlap(), "the stored setting is as it was");
    }

    @Test
    void aServerThatStopsBetweenAJobsResultAndTheGateLeavesNoCaseBehind() {
        DocumentPackage pkg = submittedCase(1);
        service.plan(trigger(pkg));
        runAll(PipelineStage.TEXT);
        runAll(PipelineStage.FIND);
        // As if the server stopped after FIND was saved: its gate's REDACT job never came to be.
        jdbc.update("DELETE FROM pipeline_jobs WHERE package_id = ? AND stage = 'REDACT'", pkg.id().value());
        assertEquals(0, missed(pkg), "a gate that may still run is left alone for a claim lease");
        jdbc.update("UPDATE pipeline_jobs SET updated_at = updated_at - interval '1 hour' WHERE package_id = ?",
                pkg.id().value());
        assertEquals(1, missed(pkg));
        service.runMissedGates();
        assertEquals(1, count(pkg, PipelineStage.REDACT));
        assertEquals(0, missed(pkg), "nothing is missed while a job is open");
    }

    private static long missed(DocumentPackage pkg) {
        return jobs.findCasesWaitingForTheirGate(Duration.ofSeconds(30), 1000).stream()
                .filter(c -> c.packageId().equals(pkg.id())).count();
    }

    @Test
    void inInlineModeWithNoStagedJobsNoWorkerStarts() throws Exception {
        StagedProcessingService inlineService = serviceWith.apply(new StagedProcessingSettings(PipelineMode.INLINE,
                Duration.ofSeconds(30), 3, 1, 1, 1, Duration.ofMillis(100)));
        var workers = new com.securedoc.platform.api.processing.StageJobWorkers(inlineService, "test");
        workers.start();
        Thread.sleep(500);
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive()
                && t.getName().matches("stage-worker-\\d+")));
        workers.stop();
    }

    // ----------------------------------------------------------------------------------------------- helpers

    private Document document(DocumentPackage pkg) {
        return documents.findByPackageIdAndTenantId(pkg.id(), pkg.tenantId()).get(0);
    }

    /** Several workers on one stage, as on one or two servers. */
    private void runWithWorkers(PipelineStage stage, int workers) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(workers);
        try {
            List<java.util.concurrent.Future<?>> running = new ArrayList<>();
            for (int w = 1; w <= workers; w++) {
                String owner = "server-" + (w % 2 == 0 ? "a" : "b") + "/worker-" + w;
                running.add(pool.submit(() -> {
                    Optional<PipelineJob> job;
                    while ((job = service.claimNext(stage, owner)).isPresent()) {
                        assertEquals(StagedProcessingService.Outcome.DONE, service.run(job.get()));
                    }
                }));
            }
            for (var r : running) r.get(120, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private void runOne(PipelineStage stage) {
        PipelineJob job = service.claimNext(stage, "server-a/" + stage + "-worker-1").orElseThrow(
                () -> new AssertionError("no " + stage + " job due"));
        assertEquals(StagedProcessingService.Outcome.DONE, service.run(job));
    }

    private void runAll(PipelineStage stage) {
        Optional<PipelineJob> job;
        while ((job = service.claimNext(stage, "server-a/" + stage + "-worker-1")).isPresent()) {
            service.run(job.get());
        }
    }

    private DocumentPackage submittedCase(int documentCount) {
        DocumentPackage pkg = DocumentPackage.create(tenant.id(), PackageType.MANUAL_UPLOAD, ActorId.generate(),
                PackageMetadata.empty());
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < documentCount; i++) {
            Document d = Document.create(tenant.id(), pkg.id(), FileName.of("doc-" + i + ".pdf"), MimeType.pdf(), 64,
                    Checksum.sha256("a".repeat(64)), DocumentType.PDF);
            pkg.addDocument(d);
            docs.add(d);
        }
        pkg.submit();
        tx.executeWithoutResult(s -> {
            packages.save(pkg);
            docs.forEach(documents::save);
        });
        return pkg;
    }

    private static TriggerPackageProcessingCommand trigger(DocumentPackage pkg) {
        return TriggerPackageProcessingCommand.of(pkg.id(), pkg.tenantId(), ActorId.generate(), CorrelationId.generate());
    }

    private static PackageStatus status(DocumentPackage pkg) {
        return packages.findByIdAndTenantId(pkg.id(), pkg.tenantId()).orElseThrow().status();
    }

    private static List<PipelineJob> jobsOf(DocumentPackage pkg) {
        return jobs.findByTenantIdAndPackageId(pkg.tenantId(), pkg.id());
    }

    private static long count(DocumentPackage pkg, PipelineStage stage) {
        return jobsOf(pkg).stream().filter(j -> j.stage() == stage).count();
    }

    /** The JPA part of the application: the entities, the Spring Data repositories, one transaction manager. */
    @Configuration
    @EnableJpaRepositories(basePackageClasses = SpringDataDocumentPackageRepository.class)
    static class JpaSetup {
        static String url;

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(url, user, password);
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.securedoc.platform.infrastructure.persistence.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of(
                    "hibernate.hbm2ddl.auto", "none",
                    "hibernate.physical_naming_strategy",
                    "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                    "hibernate.implicit_naming_strategy",
                    "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }
    }
}
