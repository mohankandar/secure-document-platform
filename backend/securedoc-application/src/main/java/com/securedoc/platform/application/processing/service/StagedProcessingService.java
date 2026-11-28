package com.securedoc.platform.application.processing.service;

import com.securedoc.platform.application.detection.command.DetectSensitiveDataCommand;
import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.extraction.command.ExtractDocumentTextCommand;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.pipeline.PageBatch;
import com.securedoc.platform.application.pipeline.PageBatchCommand;
import com.securedoc.platform.application.pipeline.PageBatchPlan;
import com.securedoc.platform.application.pipeline.StagedProcessingSettings;
import com.securedoc.platform.application.pipeline.StagedStepScope;
import com.securedoc.platform.application.port.in.DetectSensitiveDataUseCase;
import com.securedoc.platform.application.port.in.ExtractDocumentTextUseCase;
import com.securedoc.platform.application.port.in.RedactDocumentUseCase;
import com.securedoc.platform.application.port.in.StartDocumentProcessingUseCase;
import com.securedoc.platform.application.port.in.StartPackageProcessingUseCase;
import com.securedoc.platform.application.port.in.TriggerPackageProcessingUseCase;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.PipelineJobRepository;
import com.securedoc.platform.application.port.out.ProcessingLockPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TransactionPort;
import com.securedoc.platform.application.processing.command.StartDocumentProcessingCommand;
import com.securedoc.platform.application.processing.command.StartPackageProcessingCommand;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.processing.result.ProcessingWorkItem;
import com.securedoc.platform.application.processing.result.StartDocumentProcessingResult;
import com.securedoc.platform.application.processing.result.StartPackageProcessingResult;
import com.securedoc.platform.application.redaction.command.RedactDocumentCommand;
import com.securedoc.platform.application.resilience.TechnicalFailureClassifier;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.technical.service.ProcessingIncidentService;
import com.securedoc.platform.application.telemetry.ProductTelemetryService;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.business.PageBatchSettings;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineJobStatus;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import com.securedoc.platform.domain.processing.ProcessingStage;
import com.securedoc.platform.domain.vision.VisionPageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Task 0048 (staged pipeline P2 part 1): text reading, finding and redaction as per-document jobs (design
 * docs/work/staged-pipeline-design.md, sections 3 and 4).
 *
 * <p>What each step does is unchanged: a step calls the same use cases, with the same failure handling, as
 * {@link TriggerPackageProcessingService} does inline. What changes is the scheduling:
 * <ul>
 *   <li><b>Jobs.</b> A case is started by queuing one TEXT job per document. A TEXT job queues the document's FIND
 *       job. FIND and REDACT end in the case's gate.</li>
 *   <li><b>Gates.</b> After a job, the case's gate (one at a time per case, under a lock) looks at its documents: while
 *       any is still reading or finding, nothing happens; then a failed document fails the case and a finding that
 *       needs a person sends it to Review (as inline); otherwise every document waiting for redaction gets its REDACT
 *       job; when all are redacted the case is Ready for export (P1, unchanged: its EXPORT jobs are queued).</li>
 *   <li><b>Fencing (#342).</b> A step runs in one transaction. Its last write is the job's result, saved only while
 *       its claim number is still current. If it is not (the worker was paused or cut off and another worker took the
 *       job), the whole transaction rolls back: none of the step's writes count, and the files it stored are deleted.
 *       A server that stops mid-step leaves nothing half-saved either: its transaction never commits.</li>
 *   <li><b>Page batches (task 0049).</b> A document over its policy's batch size has one TEXT and one FIND job per page
 *       batch. The last batch of a stage to finish gathers the document in its own transaction (under the case's gate
 *       lock, so exactly one does), fenced like every result; only then does the document move on.</li>
 *   <li><b>Vision (task 0064).</b> A read document gets one VISION job per routed page, alongside its FIND jobs. The
 *       page is read before the step's transaction begins, so no database connection is held while the model works.
 *       The last FIND or VISION job of the document gathers it.</li>
 *   <li><b>A server stops.</b> Its claims run out; the job goes back to the queue (one attempt). After
 *       {@code maxAttempts} the case stops with LV-PRC-008.</li>
 * </ul>
 * Every job status change is audited with ids and codes only; no content.
 */
public final class StagedProcessingService implements TriggerPackageProcessingUseCase {

    private static final Logger log = LoggerFactory.getLogger(StagedProcessingService.class);
    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    private static final String DOCUMENT_PROCESSING_LOCK_PREFIX = "doc-processing:";

    /** The stages this service runs. */
    public static final Set<PipelineStage> STAGES = EnumSet.of(PipelineStage.TEXT, PipelineStage.FIND,
            PipelineStage.VISION, PipelineStage.REDACT);
    private static final Set<PipelineStage> SEARCH = EnumSet.of(PipelineStage.FIND, PipelineStage.VISION);
    private static final Set<PackageStatus> OPEN = EnumSet.of(PackageStatus.PROCESSING, PackageStatus.PENDING_REVIEW,
            PackageStatus.IN_REVIEW);

    /** What happened to a job a worker ran. */
    public enum Outcome {
        /** The result was saved. */
        DONE,
        /** The claim was no longer current: nothing was saved. */
        CLAIM_LOST
    }

    private final StagedProcessingSettings settings;
    private final PipelineJobRepository jobs;
    private final PackageRepository packageRepository;
    private final DocumentRepository documentRepository;
    private final ProcessingLockPort processingLockPort;
    private final StartPackageProcessingUseCase startPackageProcessing;
    private final StartDocumentProcessingUseCase startDocumentProcessing;
    private final ExtractDocumentTextUseCase extractDocumentText;
    private final DetectSensitiveDataUseCase detectSensitiveData;
    private final RedactDocumentUseCase redactDocument;
    private final PackageLifecycleService lifecycleService;
    private final ProcessingIncidentService incidents;
    private final PackageProcessingRecoveryService recoveryService;
    private final AuditService audit;
    private final ProductTelemetryService telemetry;
    private final TriggerPackageProcessingService inline;
    private final TransactionPort transactions;
    private final StoragePort storage;
    /** Task 0049: null splits no document. */
    private final PageBatching batching;

    /**
     * Task 0049: what planning page batches needs.
     *
     * @param settings  the entity's batch size and overlap
     * @param pageCount the number of pages of a document that is not read yet; empty when it cannot be told (the
     *                  document is then one job, whose step reports what is wrong with it)
     * @param severalPagesPerAiRequest task 0052: whether one AI request may cover several pages for the entity. Only
     *                  then do Find batches share pages: with one page per request the model never sees two pages
     *                  together, so a shared page would only be sent twice.
     */
    public record PageBatching(Function<TenantId, PageBatchSettings> settings,
                               Function<Document, OptionalInt> pageCount,
                               java.util.function.Predicate<TenantId> severalPagesPerAiRequest) {
        public PageBatching {
            Objects.requireNonNull(settings);
            Objects.requireNonNull(pageCount);
            Objects.requireNonNull(severalPagesPerAiRequest);
        }

        /** The entity's settings as they are used for Find now; the stored overlap is not changed. */
        public PageBatchSettings findSettings(TenantId tenantId) {
            PageBatchSettings stored = settings.apply(tenantId);
            return stored.overlap() == 0 || severalPagesPerAiRequest.test(tenantId) ? stored
                    : new PageBatchSettings(stored.size(), 0);
        }
    }

    public StagedProcessingService(StagedProcessingSettings settings,
                                   PipelineJobRepository jobs,
                                   PackageRepository packageRepository,
                                   DocumentRepository documentRepository,
                                   ProcessingLockPort processingLockPort,
                                   StartPackageProcessingUseCase startPackageProcessing,
                                   StartDocumentProcessingUseCase startDocumentProcessing,
                                   ExtractDocumentTextUseCase extractDocumentText,
                                   DetectSensitiveDataUseCase detectSensitiveData,
                                   RedactDocumentUseCase redactDocument,
                                   PackageLifecycleService lifecycleService,
                                   ProcessingIncidentService incidents,
                                   PackageProcessingRecoveryService recoveryService,
                                   AuditService audit,
                                   ProductTelemetryService telemetry,
                                   TriggerPackageProcessingService inline,
                                   TransactionPort transactions,
                                   StoragePort storage) {
        this(settings, jobs, packageRepository, documentRepository, processingLockPort, startPackageProcessing,
                startDocumentProcessing, extractDocumentText, detectSensitiveData, redactDocument, lifecycleService,
                incidents, recoveryService, audit, telemetry, inline, transactions, storage, null);
    }

    public StagedProcessingService(StagedProcessingSettings settings,
                                   PipelineJobRepository jobs,
                                   PackageRepository packageRepository,
                                   DocumentRepository documentRepository,
                                   ProcessingLockPort processingLockPort,
                                   StartPackageProcessingUseCase startPackageProcessing,
                                   StartDocumentProcessingUseCase startDocumentProcessing,
                                   ExtractDocumentTextUseCase extractDocumentText,
                                   DetectSensitiveDataUseCase detectSensitiveData,
                                   RedactDocumentUseCase redactDocument,
                                   PackageLifecycleService lifecycleService,
                                   ProcessingIncidentService incidents,
                                   PackageProcessingRecoveryService recoveryService,
                                   AuditService audit,
                                   ProductTelemetryService telemetry,
                                   TriggerPackageProcessingService inline,
                                   TransactionPort transactions,
                                   StoragePort storage,
                                   PageBatching batching) {
        this.batching = batching;
        this.settings = Objects.requireNonNull(settings);
        this.jobs = Objects.requireNonNull(jobs);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.processingLockPort = Objects.requireNonNull(processingLockPort);
        this.startPackageProcessing = Objects.requireNonNull(startPackageProcessing);
        this.startDocumentProcessing = Objects.requireNonNull(startDocumentProcessing);
        this.extractDocumentText = Objects.requireNonNull(extractDocumentText);
        this.detectSensitiveData = Objects.requireNonNull(detectSensitiveData);
        this.redactDocument = Objects.requireNonNull(redactDocument);
        this.lifecycleService = Objects.requireNonNull(lifecycleService);
        this.incidents = Objects.requireNonNull(incidents);
        this.recoveryService = Objects.requireNonNull(recoveryService);
        this.audit = Objects.requireNonNull(audit);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.inline = Objects.requireNonNull(inline);
        this.transactions = Objects.requireNonNull(transactions);
        this.storage = Objects.requireNonNull(storage);
    }

    public StagedProcessingSettings settings() {
        return settings;
    }

    // ------------------------------------------------------------------------------------------ routing and planning

    /** Task 0121: staged only. Every processing trigger plans the case as jobs. */
    @Override
    public void execute(TriggerPackageProcessingCommand command) {
        plan(command);
    }

    /**
     * Plans a staged case instead of running it: queues its next jobs. Runs on the dispatcher's thread under the
     * case's processing lease, as an inline run does; each change is one transaction.
     */
    public void plan(TriggerPackageProcessingCommand command) {
        Objects.requireNonNull(command, "Command must not be null");
        DocumentPackage pkg = packageRepository.findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));
        log.info("STAGED_PROCESSING_PLAN package={} tenant={} status={} correlation={}",
                command.packageId(), command.tenantId(), pkg.status(), command.correlationId());
        switch (pkg.status()) {
            case SUBMITTED -> {
                inline.ensureDocumentIntelligenceSnapshot(command);
                transactions.inNewTransaction(() -> {
                    start(command);
                    return null;
                });
            }
            case PROCESSING -> {
                inline.ensureDocumentIntelligenceSnapshot(command);
                transactions.inNewTransaction(() -> {
                    // Task 0121: a case an older version was running inline when it stopped has no stage jobs; the
                    // documents that run left half done start again from the kept original, then it goes on as jobs.
                    if (!jobs.hasStageJobs(command.tenantId(), command.packageId())) inline.rewindInterruptedDocuments(command);
                    queueMissingJobs(command.tenantId(), command.packageId(), command.correlationId());
                    return null;
                });
                gate(command.tenantId(), command.packageId(), command.correlationId());
            }
            case PENDING_REVIEW, IN_REVIEW -> {
                inline.ensureDocumentIntelligenceSnapshot(command);
                transactions.inNewTransaction(() -> {
                    continueAfterReview(command);
                    return null;
                });
            }
            default -> throw new ValidationException(LvErrorCode.PRC_009,
                    "Cannot trigger processing for package in status: " + pkg.status());
        }
    }

    /** SUBMITTED to PROCESSING (the same use case as inline) and one TEXT job per document, in one transaction. */
    private void start(TriggerPackageProcessingCommand command) {
        StartPackageProcessingResult result = startPackageProcessing.execute(StartPackageProcessingCommand.of(
                command.packageId(), command.tenantId(), command.correlationId()));
        int cycle = lifecycleService.nextExportCycle(command.packageId(), command.tenantId());
        // A case that starts again in the same run (a technical retry) starts with no old job: a finished one would
        // stop its document's job from being queued (task 0049).
        jobs.removeStageJobs(command.tenantId(), command.packageId(), cycle);
        for (ProcessingWorkItem item : result.workItems()) {
            queueText(command.tenantId(), command.packageId(), item.documentId(), cycle, command.correlationId());
        }
    }

    /**
     * A staged case triggered again while PROCESSING: a document waiting for a stage without a job for it gets one.
     * Queuing a job that exists adds nothing.
     */
    private void queueMissingJobs(TenantId tenantId, PackageId packageId, CorrelationId correlationId) {
        int cycle = lifecycleService.nextExportCycle(packageId, tenantId);
        List<PipelineJob> existing = jobs.findByTenantIdAndPackageId(tenantId, packageId);
        for (Document document : documentRepository.findByPackageIdAndTenantId(packageId, tenantId)) {
            if (document.status() == DocumentStatus.UPLOADED
                    && !hasJob(existing, PipelineStage.TEXT, document.id(), cycle)) {
                queueText(tenantId, packageId, document.id(), cycle, correlationId);
            } else if (document.status() == DocumentStatus.DETECTING
                    && !hasJob(existing, PipelineStage.FIND, document.id(), cycle)) {
                queueFind(tenantId, packageId, document, cycle, correlationId);
            }
        }
    }

    /** A document keeps the batches it was split into, also when the entity's batch settings change meanwhile. */
    private static boolean hasJob(List<PipelineJob> jobs, PipelineStage stage, DocumentId documentId, int cycle) {
        return jobs.stream().anyMatch(job -> job.stage() == stage && documentId.equals(job.documentId())
                && job.exportCycle() == cycle);
    }

    /** The document's TEXT job, or one per page batch of a large document. */
    private void queueText(TenantId tenantId, PackageId packageId, DocumentId documentId, int cycle,
                           CorrelationId correlationId) {
        List<PageBatchPlan.Range> ranges = List.of();
        if (batching != null) {
            OptionalInt pages = documentRepository.findByIdAndTenantId(documentId, tenantId)
                    .map(batching.pageCount()).orElse(OptionalInt.empty());
            if (pages.isPresent()) ranges = PageBatchPlan.text(pages.getAsInt(), batching.settings().apply(tenantId));
        }
        queueRanges(PipelineStage.TEXT, tenantId, packageId, documentId, ranges, cycle, correlationId);
    }

    /** The read document's FIND job, or one per page batch of a large document. */
    private void queueFind(TenantId tenantId, PackageId packageId, Document document, int cycle,
                           CorrelationId correlationId) {
        List<PageBatchPlan.Range> ranges = batching == null ? List.of()
                : PageBatchPlan.find(document.pageCount(), batching.findSettings(tenantId));
        List<Integer> visionPages = detectSensitiveData.pagesForVision(DetectSensitiveDataCommand.of(document.id(),
                packageId, tenantId, correlationId));
        if (ranges.isEmpty() && !visionPages.isEmpty()) {
            // The search and the vision pages are gathered together, so the search is a batch too.
            ranges = List.of(new PageBatchPlan.Range(1, document.pageCount()));
        }
        queueRanges(PipelineStage.FIND, tenantId, packageId, document.id(), ranges, cycle, correlationId);
        for (int page : visionPages) {
            PipelineJob job = PipelineJob.readyBatch(tenantId, PipelineStage.VISION, packageId, document.id(), page,
                    page, cycle, Instant.now());
            if (jobs.enqueue(job)) auditQueued(job, correlationId);
        }
    }

    private void queueRanges(PipelineStage stage, TenantId tenantId, PackageId packageId, DocumentId documentId,
                             List<PageBatchPlan.Range> ranges, int cycle, CorrelationId correlationId) {
        if (ranges.isEmpty()) {
            queue(stage, tenantId, packageId, documentId, cycle, correlationId, false);
            return;
        }
        for (PageBatchPlan.Range range : ranges) {
            PipelineJob job = PipelineJob.readyBatch(tenantId, stage, packageId, documentId, range.from(), range.to(),
                    cycle, Instant.now());
            if (jobs.enqueue(job)) auditQueued(job, correlationId);
        }
    }

    private void auditQueued(PipelineJob job, CorrelationId correlationId) {
        audit.recordSuccess(job.tenantId(), SYSTEM_ACTOR, AuditAction.PIPELINE_JOB_QUEUED, "DocumentPackage",
                job.packageId().toString(), job.packageId(), correlationId,
                "Pipeline job queued: stage=" + job.stage() + ", document=" + job.documentId() + pagesText(job)
                        + ", cycle=" + job.exportCycle());
    }

    private static String pagesText(PipelineJob job) {
        return job.isPageBatch() ? ", pages=" + job.pageFrom() + "-" + job.pageTo() : "";
    }

    /** As inline: every document waiting for redaction is cleared by its reviewer; then each gets its REDACT job. */
    private void continueAfterReview(TriggerPackageProcessingCommand command) {
        List<Document> redacting = documentRepository
                .findByPackageIdAndTenantId(command.packageId(), command.tenantId()).stream()
                .filter(document -> document.status() == DocumentStatus.REDACTING)
                .toList();
        if (redacting.isEmpty()) {
            throw new ValidationException(LvErrorCode.REV_003, "No documents in REDACTING status require processing");
        }
        for (Document document : redacting) {
            if (inline.hasDetectedFindings(document.id(), command.tenantId())) {
                throw new ValidationException(LvErrorCode.REV_003, "Cannot continue redaction: document "
                        + document.id() + " still has findings requiring review");
            }
        }
        int cycle = lifecycleService.nextExportCycle(command.packageId(), command.tenantId());
        for (Document document : redacting) {
            queue(PipelineStage.REDACT, command.tenantId(), command.packageId(), document.id(), cycle,
                    command.correlationId(), true);
        }
    }

    /**
     * Queues a job. Queuing one that exists adds nothing; with {@code again}, a finished one for the same case run is
     * set back to READY (a document redacted again after a second review).
     */
    private void queue(PipelineStage stage, TenantId tenantId, PackageId packageId, DocumentId documentId, int cycle,
                       CorrelationId correlationId, boolean again) {
        PipelineJob job = PipelineJob.ready(tenantId, stage, packageId, documentId, null, null, cycle, Instant.now());
        if (jobs.enqueue(job)) {
            auditQueued(job, correlationId);
            return;
        }
        if (!again) return;
        jobs.findByTenantIdAndPackageId(tenantId, packageId).stream()
                .filter(existing -> existing.stage() == stage && documentId.equals(existing.documentId())
                        && existing.exportCycle() == cycle && existing.status() == PipelineJobStatus.DONE)
                .findFirst()
                .ifPresent(existing -> {
                    if (jobs.changeStatus(tenantId, existing.id(), PipelineJobStatus.DONE, PipelineJobStatus.READY, 0,
                            null, "QUEUED_AGAIN")) {
                        auditStatus(existing, PipelineJobStatus.DONE, PipelineJobStatus.READY, null, "QUEUED_AGAIN",
                                correlationId);
                    }
                });
    }

    // ------------------------------------------------------------------------------------------------- workers

    /** Task 0049: true while staged work is still to be done (after a switch back to inline it is finished first). */
    public boolean hasOpenJobs() {
        return jobs.hasOpenStageJobs();
    }

    /** The next due job of one stage, claimed with a new claim number. */
    public Optional<PipelineJob> claimNext(PipelineStage stage, String owner) {
        if (!STAGES.contains(stage)) throw new IllegalArgumentException("Not a staged processing stage: " + stage);
        // Stage jobs have no destination, so the per-destination limit does not apply.
        return jobs.claimNext(EnumSet.of(stage), owner, settings.claimLease(), 1);
    }

    /** Task 0079: as above; a Bulk job only of these sources. */
    public Optional<PipelineJob> claimNext(PipelineStage stage, String owner, java.util.Set<String> openBulkSources) {
        if (!STAGES.contains(stage)) throw new IllegalArgumentException("Not a staged processing stage: " + stage);
        return jobs.claimNext(EnumSet.of(stage), owner, settings.claimLease(), 1, openBulkSources);
    }

    /** Renews a running job's claim. False when the claim is no longer this worker's. */
    public boolean renew(PipelineJob job) {
        return jobs.renew(job.id(), job.claimToken(), settings.claimLease());
    }

    /**
     * Runs one claimed job: its step and its result in one transaction (see the class comment), then the case's gate.
     */
    public Outcome run(PipelineJob job) {
        Objects.requireNonNull(job, "job");
        CorrelationId correlationId = CorrelationId.generate();
        List<ArtifactStorageReference> stored = new ArrayList<>();
        List<ArtifactStorageReference> discarded = new ArrayList<>();
        if (job.stage() == PipelineStage.FIND && job.isPageBatch()) prepareFind(job, correlationId);
        VisionPageResult visionPage = job.stage() == PipelineStage.VISION ? readVisionPage(job, correlationId) : null;
        try {
            transactions.inNewTransaction(() -> StagedStepScope.tracking(stored, discarded, () -> {
                runStep(job, correlationId, visionPage);
                return null;
            }));
            // Saved: the batch results this step gathered are no longer needed.
            deleteStored(discarded);
        } catch (ClaimLost lost) {
            deleteStored(stored);
            log.warn("PIPELINE_JOB_CLAIM_LOST job={} stage={} package={} claimToken={} reasonCode=RESULT_NOT_SAVED",
                    job.id(), job.stage(), job.packageId(), job.claimToken());
            return Outcome.CLAIM_LOST;
        } catch (RuntimeException notSaved) {
            // The step's writes could not be committed (for example a database error left the transaction unusable).
            // Record the failure as inline does, in a new transaction, still fenced by the claim number.
            deleteStored(stored);
            try {
                transactions.inNewTransaction(() -> {
                    Document document = documentRepository.findByIdAndTenantId(job.documentId(), job.tenantId())
                            .orElse(null);
                    recordStepFailure(job, document, processingStage(job.stage()), notSaved, correlationId);
                    finishOrThrow(job, LvErrorCodes.forPlatformFailure(notSaved).code(), "STEP_NOT_SAVED",
                            correlationId);
                    return null;
                });
            } catch (ClaimLost lost) {
                log.warn("PIPELINE_JOB_CLAIM_LOST job={} stage={} package={} claimToken={} reasonCode=FAILURE_NOT_SAVED",
                        job.id(), job.stage(), job.packageId(), job.claimToken());
                return Outcome.CLAIM_LOST;
            }
        }
        gateSafely(job.tenantId(), job.packageId(), correlationId);
        return Outcome.DONE;
    }

    /** Task 0065: outside any transaction; the step does it itself, and records why, when this failed. */
    private void prepareFind(PipelineJob job, CorrelationId correlationId) {
        try {
            if (!jobs.claimIsCurrent(job.id(), job.claimToken())) return;
            boolean detecting = documentRepository.findByIdAndTenantId(job.documentId(), job.tenantId())
                    .map(document -> document.status() == DocumentStatus.DETECTING).orElse(false);
            boolean open = packageRepository.findByIdAndTenantId(job.packageId(), job.tenantId())
                    .map(pkg -> OPEN.contains(pkg.status())).orElse(false);
            if (detecting && open) detectSensitiveData.prepareBatch(wholeDocument(job, correlationId));
        } catch (RuntimeException failure) {
            // The server is stopping: the job's claim runs out and another worker takes it.
            if (Thread.currentThread().isInterrupted()) throw failure;
            log.warn("DOCUMENT_SUMMARY_NOT_PREPARED job={} package={} type={}", job.id(), job.packageId(),
                    failure.getClass().getSimpleName());
        }
    }

    /**
     * Task 0064: the page, read outside any transaction; null when there is nothing to read or it failed (the step then
     * finds out why inside its own transaction, and records it).
     */
    private VisionPageResult readVisionPage(PipelineJob job, CorrelationId correlationId) {
        try {
            if (!jobs.claimIsCurrent(job.id(), job.claimToken())) return null;
            boolean open = packageRepository.findByIdAndTenantId(job.packageId(), job.tenantId())
                    .map(pkg -> OPEN.contains(pkg.status())).orElse(false);
            boolean detecting = documentRepository.findByIdAndTenantId(job.documentId(), job.tenantId())
                    .map(document -> document.status() == DocumentStatus.DETECTING).orElse(false);
            return open && detecting ? detectSensitiveData.readVisionPage(wholeDocument(job, correlationId),
                    job.pageFrom()) : null;
        } catch (RuntimeException failure) {
            // The server is stopping: the job's claim runs out and another worker reads the page.
            if (Thread.currentThread().isInterrupted()) throw failure;
            log.warn("VISION_PAGE_READ_FAILED job={} package={} type={}", job.id(), job.packageId(),
                    failure.getClass().getSimpleName());
            return null;
        }
    }

    private static DetectSensitiveDataCommand wholeDocument(PipelineJob job, CorrelationId correlationId) {
        return DetectSensitiveDataCommand.of(job.documentId(), job.packageId(), job.tenantId(), correlationId);
    }

    private void runStep(PipelineJob job, CorrelationId correlationId, VisionPageResult visionPage) {
        if (!jobs.claimIsCurrent(job.id(), job.claimToken())) throw new ClaimLost();
        DocumentPackage pkg = packageRepository.findByIdAndTenantId(job.packageId(), job.tenantId()).orElse(null);
        if (pkg == null || !OPEN.contains(pkg.status())) {
            // The case was stopped (a document failed, LV-PRC-008, cancelled): its remaining jobs do nothing.
            finishOrThrow(job, PipelineJobStatus.STOPPED, null, "CASE_NOT_OPEN", correlationId);
            return;
        }
        Document document = documentRepository.findByIdAndTenantId(job.documentId(), job.tenantId()).orElse(null);
        if (document == null) {
            finishOrThrow(job, PipelineJobStatus.STOPPED, null, "DOCUMENT_NOT_FOUND", correlationId);
            return;
        }
        String code = null;
        String detail = null;
        try {
            detail = switch (job.stage()) {
                case TEXT -> text(job, document, correlationId);
                case FIND -> find(job, document, correlationId);
                case VISION -> vision(job, document, correlationId, visionPage);
                case REDACT -> redact(job, document, correlationId);
                default -> throw new IllegalStateException("Not a staged processing stage: " + job.stage());
            };
        } catch (ClaimLost lost) {
            throw lost;
        } catch (ValidationException precondition) {
            if (job.stage() != PipelineStage.REDACT) {
                recordStepFailure(job, document, processingStage(job.stage()), precondition, correlationId);
            }
            // REDACT: a review precondition is not a technical failure (as inline); the gate decides what follows.
            code = precondition.lvCode() == null ? null : precondition.lvCode().code();
            detail = job.stage() == PipelineStage.REDACT ? "REVIEW_PRECONDITION" : "STEP_FAILED";
        } catch (StepFailure failure) {
            recordStepFailure(job, document, failure.stage, failure.cause, correlationId);
            code = LvErrorCodes.forPlatformFailure(failure.cause).code();
            detail = "STEP_FAILED";
        } catch (RuntimeException failure) {
            recordStepFailure(job, document, processingStage(job.stage()), failure, correlationId);
            code = LvErrorCodes.forPlatformFailure(failure).code();
            detail = "STEP_FAILED";
        }
        finishOrThrow(job, code, detail, correlationId);
    }

    /** TEXT: the document's text is read (native text or OCR), as inline; then its FIND job is queued. */
    private String text(PipelineJob job, Document document, CorrelationId correlationId) {
        if (document.status() != DocumentStatus.UPLOADED) {
            // Already read by an earlier run of this job whose result was saved: only the next job is missing.
            if (document.status() == DocumentStatus.DETECTING) {
                if (!hasJob(jobs.findByTenantIdAndPackageId(job.tenantId(), job.packageId()), PipelineStage.FIND,
                        document.id(), job.exportCycle())) {
                    queueFind(job.tenantId(), job.packageId(), document, job.exportCycle(), correlationId);
                }
            }
            return "ALREADY_" + document.status();
        }
        if (job.isPageBatch()) return textBatch(job, document, correlationId);
        startAndRead(job, document, correlationId, List.of());
        queueFind(job.tenantId(), job.packageId(), reload(job, document), job.exportCycle(), correlationId);
        return null;
    }

    /** One page batch of TEXT; the last one gathers the document's text. */
    private String textBatch(PipelineJob job, Document document, CorrelationId correlationId) {
        try {
            extractDocumentText.extractBatch(command(job, correlationId));
        } catch (RuntimeException failure) {
            throw new StepFailure(ProcessingStage.TEXT_EXTRACTION, failure);
        }
        List<PipelineJob> batches = lastOfItsStage(job);
        if (batches.isEmpty()) return null;
        startAndRead(job, document, correlationId, batchesOf(batches, PipelineStage.TEXT));
        queueFind(job.tenantId(), job.packageId(), reload(job, document), job.exportCycle(), correlationId);
        return null;
    }

    private static PageBatchCommand command(PipelineJob job, CorrelationId correlationId) {
        return new PageBatchCommand(new PageBatch(job.id(), job.pageFrom(), job.pageTo()), job.documentId(),
                job.packageId(), job.tenantId(), correlationId);
    }

    private Document reload(PipelineJob job, Document document) {
        return documentRepository.findByIdAndTenantId(document.id(), job.tenantId()).orElse(document);
    }

    /**
     * Every batch of the job's document and stage when the others are all done (this one's result is in this
     * transaction); otherwise empty. The gate lock is held until this transaction ends, so of two batches finishing
     * together the second sees the first as done. A batch that failed (its document is then FAILED) is never gathered.
     */
    private List<PipelineJob> lastOfItsStage(PipelineJob job) {
        jobs.lockCaseGate(job.tenantId(), job.packageId());
        List<PipelineJob> batches = jobs.findByTenantIdAndPackageId(job.tenantId(), job.packageId()).stream()
                .filter(other -> gatheredTogether(other.stage(), job.stage())
                        && job.documentId().equals(other.documentId()) && other.exportCycle() == job.exportCycle())
                .sorted(java.util.Comparator.comparingInt(PipelineJob::pageFrom)).toList();
        boolean othersDone = batches.stream().filter(other -> !other.id().equals(job.id()))
                .allMatch(other -> other.status() == PipelineJobStatus.DONE && other.lastLvCode() == null
                        && other.lastDetail() == null);
        return othersDone ? batches : List.of();
    }

    /** Task 0064: a document's search and its vision pages are one gathering. */
    private static boolean gatheredTogether(PipelineStage a, PipelineStage b) {
        return a == b || (SEARCH.contains(a) && SEARCH.contains(b));
    }

    private static List<PageBatch> batchesOf(List<PipelineJob> jobs, PipelineStage stage) {
        return jobs.stream().filter(job -> job.stage() == stage)
                .map(job -> new PageBatch(job.id(), job.pageFrom(), job.pageTo())).toList();
    }

    /** UPLOADED to DETECTING: the document is read, or its read batches are gathered. */
    private void startAndRead(PipelineJob job, Document document, CorrelationId correlationId,
                              List<PageBatch> batches) {
        if (job.attempts() > 0) {
            // A lost run's document lock (an inline run's, or one written outside this transaction) must not refuse
            // the retry. While this job is claimed no other worker works on this document.
            processingLockPort.forceRelease(DOCUMENT_PROCESSING_LOCK_PREFIX + document.id().value());
        }
        ProcessingStage stage = ProcessingStage.DOCUMENT_PROCESSING;
        try {
            StartDocumentProcessingResult start = startDocumentProcessing.execute(StartDocumentProcessingCommand.of(
                    document.id(), job.packageId(), job.tenantId(), correlationId));
            stage = ProcessingStage.TEXT_EXTRACTION;
            ExtractDocumentTextCommand read = ExtractDocumentTextCommand.of(document.id(), job.packageId(),
                    job.tenantId(), start.lockKey(), start.lockToken(), correlationId);
            if (batches.isEmpty()) extractDocumentText.execute(read);
            else extractDocumentText.completeFromBatches(read, batches);
        } catch (RuntimeException failure) {
            throw new StepFailure(stage, failure);
        }
    }

    /** FIND: sensitive data is found (rules and AI), as inline. What follows is the gate's decision. */
    private String find(PipelineJob job, Document document, CorrelationId correlationId) {
        if (document.status() != DocumentStatus.DETECTING) return "ALREADY_" + document.status();
        try {
            if (!job.isPageBatch()) {
                detectSensitiveData.execute(wholeDocument(job, correlationId));
            } else {
                detectSensitiveData.detectBatch(command(job, correlationId));
                completeIfLast(job, correlationId);
            }
        } catch (RuntimeException failure) {
            throw new StepFailure(ProcessingStage.SENSITIVE_DETECTION, failure);
        }
        return null;
    }

    /** VISION (task 0064): one routed page's result is kept; the last search or vision job gathers the document. */
    private String vision(PipelineJob job, Document document, CorrelationId correlationId, VisionPageResult result) {
        if (document.status() != DocumentStatus.DETECTING) return "ALREADY_" + document.status();
        try {
            detectSensitiveData.saveVisionPage(command(job, correlationId), result != null ? result
                    : detectSensitiveData.readVisionPage(wholeDocument(job, correlationId), job.pageFrom()));
            completeIfLast(job, correlationId);
        } catch (RuntimeException failure) {
            throw new StepFailure(ProcessingStage.SENSITIVE_DETECTION, failure);
        }
        return null;
    }

    private void completeIfLast(PipelineJob job, CorrelationId correlationId) {
        List<PipelineJob> search = lastOfItsStage(job);
        if (search.isEmpty()) return;
        detectSensitiveData.completeFromBatches(wholeDocument(job, correlationId),
                batchesOf(search, PipelineStage.FIND), batchesOf(search, PipelineStage.VISION));
    }

    /** REDACT: the document is redacted with its safety check and the final check (task 0044), as inline. */
    private String redact(PipelineJob job, Document document, CorrelationId correlationId) {
        if (document.status() != DocumentStatus.REDACTING) return "ALREADY_" + document.status();
        if (inline.hasDetectedFindings(document.id(), job.tenantId())) {
            // Task 0119: one review stop per case: the final check's findings join the review now.
            redactDocument.checkBeforeReview(RedactDocumentCommand.of(document.id(), job.packageId(), job.tenantId(),
                    correlationId));
            return "WAITING_FOR_REVIEW";
        }
        try {
            redactDocument.execute(RedactDocumentCommand.of(document.id(), job.packageId(), job.tenantId(),
                    correlationId));
        } catch (ValidationException precondition) {
            throw precondition;
        } catch (RuntimeException failure) {
            throw new StepFailure(ProcessingStage.REDACTION, failure);
        }
        // Task 0112: the final check (or the second check) held pages for a person and nothing was kept. Said in the
        // job, so the gate runs it again once a person has checked them (the final check then runs again too).
        // A run that kept nothing and asked no one is not held: the gate stops its case (LV-PRC-011), never a loop.
        if (reload(job, document).status() != DocumentStatus.REDACTING) return null;
        return inline.hasDetectedFindings(document.id(), job.tenantId()) ? HELD_FOR_A_PERSON : "KEPT_NOTHING";
    }

    /** Task 0112: the REDACT job's detail when its run kept nothing and sent pages to a person. */
    static final String HELD_FOR_A_PERSON = "HELD_FOR_A_PERSON";

    /**
     * Task 0112: a finished REDACT run that waited for a person. Once nothing is left for a person, the gate runs it
     * again. Each run again needs a person's decision in between, so it can never loop by itself.
     */
    private static boolean waitedForAPerson(PipelineJob job) {
        return HELD_FOR_A_PERSON.equals(job.lastDetail()) || "WAITING_FOR_REVIEW".equals(job.lastDetail());
    }

    /** As inline: a Support incident for the stage, and the document FAILED with a safe reason. */
    private void recordStepFailure(PipelineJob job, Document document, ProcessingStage stage, Exception failure,
                                   CorrelationId correlationId) {
        incidents.recordFailure(job.tenantId(), job.packageId(), job.documentId(), stage, failure, correlationId,
                job.stage() == PipelineStage.REDACT ? "Document redaction failed" : "Document processing failed",
                Map.of());
        if (document != null) {
            inline.markDocumentFailedIfNeeded(document.id(), job.tenantId(), correlationId,
                    TechnicalFailureClassifier.safeAuditDetail(failure, job.stage() == PipelineStage.REDACT
                            ? "Document redaction failed" : "Document processing failed"));
        }
    }

    private void finishOrThrow(PipelineJob job, String code, String detail, CorrelationId correlationId) {
        finishOrThrow(job, PipelineJobStatus.DONE, code, detail, correlationId);
    }

    /** The fenced save: the job's result, only while its claim number is current; otherwise nothing counts. */
    private void finishOrThrow(PipelineJob job, PipelineJobStatus status, String code, String detail,
                               CorrelationId correlationId) {
        boolean saved = jobs.finish(job.id(), job.claimToken(), new PipelineJobRepository.Finish(status,
                job.attempts(), null, code, detail, false, false, code != null));
        if (!saved) throw new ClaimLost();
        auditStatus(job, PipelineJobStatus.RUNNING, status, code, detail, correlationId);
    }

    private void deleteStored(List<ArtifactStorageReference> stored) {
        for (ArtifactStorageReference reference : stored) {
            try {
                storage.delete(reference);
            } catch (RuntimeException failure) {
                log.warn("PIPELINE_JOB_STORED_FILE_DELETE_FAILED type={}", failure.getClass().getSimpleName());
            }
        }
    }

    // --------------------------------------------------------------------------------------------------- gates

    private void gateSafely(TenantId tenantId, PackageId packageId, CorrelationId correlationId) {
        try {
            gate(tenantId, packageId, correlationId);
        } catch (RuntimeException failure) {
            // The job's result is saved; the gate runs again after the case's next job, or when it is triggered.
            log.warn("PIPELINE_CASE_GATE_FAILED package={} type={} code={}", packageId,
                    failure.getClass().getSimpleName(), LvErrorCodes.forPlatformFailure(failure).code());
        }
    }

    /**
     * The case's gate, one at a time per case. Reads only saved document states, so the last of two documents to
     * finish always sees both.
     */
    void gate(TenantId tenantId, PackageId packageId, CorrelationId correlationId) {
        PackageStatus before = packageRepository.findByIdAndTenantId(packageId, tenantId)
                .map(DocumentPackage::status).orElse(null);
        transactions.inNewTransaction(() -> {
            jobs.lockCaseGate(tenantId, packageId);
            decide(tenantId, packageId, correlationId);
            return null;
        });
        PackageStatus after = packageRepository.findByIdAndTenantId(packageId, tenantId)
                .map(DocumentPackage::status).orElse(null);
        if (before != after) {
            log.info("PIPELINE_CASE_GATE_MOVED package={} from={} to={}", packageId, before, after);
            try {
                int documents = documentRepository.findByPackageIdAndTenantId(packageId, tenantId).size();
                telemetry.processingFinished(tenantId, packageId, after == null ? "UNKNOWN" : after.name(), 0L,
                        documents);
            } catch (RuntimeException ignored) {
                // Product telemetry must never mask processing outcome.
            }
        }
    }

    private void decide(TenantId tenantId, PackageId packageId, CorrelationId correlationId) {
        // Task 0119: the case's jobs are read first, before the case and its documents. A job and its document's result
        // are saved together, so a job read as finished always has its document's new state in the reads after it (the
        // case loads its documents, so it comes after too). Read the other way round, a document finishing in between
        // was seen still waiting with its job finished, and a good case was stopped (LV-PRC-011).
        List<PipelineJob> caseJobs = jobs.findByTenantIdAndPackageId(tenantId, packageId);
        DocumentPackage pkg = packageRepository.findByIdAndTenantId(packageId, tenantId).orElse(null);
        if (pkg == null || !OPEN.contains(pkg.status())) return;
        List<Document> documents = documentRepository.findByPackageIdAndTenantId(packageId, tenantId);
        if (documents.isEmpty()) return;
        boolean stillReadingOrFinding = documents.stream().anyMatch(document ->
                document.status() == DocumentStatus.UPLOADED
                        || document.status() == DocumentStatus.EXTRACTING
                        || document.status() == DocumentStatus.DETECTING);
        if (stillReadingOrFinding) return;
        boolean anyFailed = documents.stream().anyMatch(document -> document.status() == DocumentStatus.FAILED);
        boolean needsReview = documents.stream().anyMatch(document ->
                inline.hasDetectedFindings(document.id(), tenantId));
        if (!anyFailed && needsReview && pkg.status() == PackageStatus.PROCESSING
                && checkEveryDocumentBeforeReview(tenantId, packageId, documents, caseJobs, correlationId)) {
            return;
        }
        if (anyFailed || needsReview) {
            // Inline's own gate: a failed document fails the case; a finding for a person sends it to Review.
            inline.completeStagedPackageIfReady(packageId, tenantId, correlationId);
            return;
        }
        List<Document> waitingForRedaction = documents.stream()
                .filter(document -> document.status() == DocumentStatus.REDACTING).toList();
        if (!waitingForRedaction.isEmpty()) {
            int cycle = lifecycleService.nextExportCycle(packageId, tenantId);
            List<PipelineJob> existing = caseJobs;
            List<Document> nothingPlanned = new ArrayList<>();
            for (Document document : waitingForRedaction) {
                PipelineJob done = pkg.status() != PackageStatus.PROCESSING ? null : existing.stream()
                        .filter(job -> job.stage() == PipelineStage.REDACT && document.id().equals(job.documentId())
                                && job.exportCycle() == cycle && job.status() == PipelineJobStatus.DONE)
                        .findFirst().orElse(null);
                if (done == null) {
                    queue(PipelineStage.REDACT, tenantId, packageId, document.id(), cycle, correlationId, false);
                } else if (waitedForAPerson(done)) {
                    // Task 0112: the person has checked what the last run held; it runs again, final check included.
                    queue(PipelineStage.REDACT, tenantId, packageId, document.id(), cycle, correlationId, true);
                } else {
                    nothingPlanned.add(document);
                }
            }
            if (!nothingPlanned.isEmpty()) stopNothingPlanned(tenantId, packageId, nothingPlanned, correlationId);
            return;
        }
        // Every document is redacted: Ready for export (final approval if the entity asks for it), as inline. The case
        // always ends in an export job, whatever export-mode says now: nothing is sent in this transaction (task 0052).
        inline.completeStagedPackageIfReady(packageId, tenantId, correlationId);
    }

    /** Task 0119: a REDACT job that is still to run (or running). */
    private static final Set<PipelineJobStatus> OPEN_JOB = EnumSet.of(PipelineJobStatus.READY, PipelineJobStatus.RUNNING,
            PipelineJobStatus.WAITING_RETRY, PipelineJobStatus.PAUSED);

    /**
     * Task 0119 (owner: one review stop per case). Before the case goes to Review, every document waiting for
     * redaction has its REDACT run in this cycle: one with nothing for a person is redacted and checked (its final
     * check may hold pages), one with findings for a person has the checks run on a trial file, so all its items are in
     * this review. True while a run is queued or still running: the case goes to Review when the last one ends.
     */
    private boolean checkEveryDocumentBeforeReview(TenantId tenantId, PackageId packageId, List<Document> documents,
                                                   List<PipelineJob> existing, CorrelationId correlationId) {
        int cycle = lifecycleService.nextExportCycle(packageId, tenantId);
        boolean waiting = false;
        for (Document document : documents) {
            if (document.status() != DocumentStatus.REDACTING) continue;
            PipelineJob run = existing.stream()
                    .filter(job -> job.stage() == PipelineStage.REDACT && document.id().equals(job.documentId())
                            && job.exportCycle() == cycle)
                    .findFirst().orElse(null);
            if (run == null) {
                queue(PipelineStage.REDACT, tenantId, packageId, document.id(), cycle, correlationId, false);
                waiting = true;
            } else if (OPEN_JOB.contains(run.status())) {
                waiting = true;
            }
        }
        return waiting;
    }

    /**
     * Task 0112: a case in PROCESSING whose document waits for redaction, its redaction run finished, and nothing is
     * planned for it. It would never move again, so it stops (shown as Stopped) with LV-PRC-011 and an error in the log.
     */
    private void stopNothingPlanned(TenantId tenantId, PackageId packageId, List<Document> documents,
                                    CorrelationId correlationId) {
        log.error("PIPELINE_CASE_NOTHING_PLANNED package={} documents={} lvCode={}", packageId, documents.size(),
                LvErrorCode.PRC_011.code());
        recoveryService.stopNothingPlanned(packageId, tenantId, correlationId);
    }

    // ------------------------------------------------------------------------------------------- expired claims

    /**
     * Stage jobs whose claim ran out (a server stopped mid-job) go back to the queue; that counts as an attempt. A job
     * whose claim ran out {@code maxAttempts} times goes to NEEDS_IT with LV-PRC-008 and stops its case.
     */
    public int releaseExpiredClaims() {
        List<PipelineJob> released = jobs.releaseExpiredClaims(STAGES, settings.maxAttempts(),
                LvErrorCode.PRC_008.code(), 100);
        for (PipelineJob job : released) {
            CorrelationId correlationId = CorrelationId.generate();
            auditStatus(job, PipelineJobStatus.RUNNING, job.status(), job.lastLvCode(), job.lastDetail(),
                    correlationId);
            log.warn("PIPELINE_JOB_CLAIM_EXPIRED job={} stage={} package={} attempts={} status={}", job.id(),
                    job.stage(), job.packageId(), job.attempts(), job.status());
            if (job.status() == PipelineJobStatus.NEEDS_IT) {
                try {
                    transactions.inNewTransaction(() -> recoveryService.exhaustStagedCase(job.packageId(),
                            job.tenantId(), job.claimExpiries(), settings.maxAttempts(), correlationId));
                } catch (RuntimeException failure) {
                    log.error("PIPELINE_CASE_STOP_FAILED package={} type={}", job.packageId(),
                            failure.getClass().getSimpleName());
                }
            }
        }
        return released.size();
    }

    /**
     * Task 0049: runs the gate of cases it was missed for (a server stopped right after a job's result was saved).
     * Waits a claim lease first, so it never races the server that is about to run it; the gate is idempotent.
     */
    public int runMissedGates() {
        List<PipelineJobRepository.CaseRef> waiting = jobs.findCasesWaitingForTheirGate(settings.claimLease(), 100);
        for (PipelineJobRepository.CaseRef ref : waiting) {
            gateSafely(ref.tenantId(), ref.packageId(), CorrelationId.generate());
        }
        return waiting.size();
    }

    // --------------------------------------------------------------------------------------------------- helpers

    private void auditStatus(PipelineJob job, PipelineJobStatus from, PipelineJobStatus to, String code,
                             String detail, CorrelationId correlationId) {
        String text = "Pipeline job status: job=" + job.id() + ", stage=" + job.stage() + ", document="
                + job.documentId() + pagesText(job) + ", from=" + from + ", to=" + to + ", attempts=" + job.attempts()
                + ", claimToken=" + job.claimToken()
                + (code == null ? "" : ", code=" + code) + (detail == null ? "" : ", detail=" + detail);
        if (code == null && (to == PipelineJobStatus.DONE || to == PipelineJobStatus.READY)) {
            audit.recordSuccess(job.tenantId(), SYSTEM_ACTOR, AuditAction.PIPELINE_JOB_STATUS_CHANGED,
                    "DocumentPackage", job.packageId().toString(), job.packageId(), correlationId, text);
        } else {
            audit.recordFailure(job.tenantId(), SYSTEM_ACTOR, AuditAction.PIPELINE_JOB_STATUS_CHANGED,
                    "DocumentPackage", job.packageId().toString(), job.packageId(), correlationId, text);
        }
    }

    private static ProcessingStage processingStage(PipelineStage stage) {
        return switch (stage) {
            case TEXT -> ProcessingStage.TEXT_EXTRACTION;
            case FIND, VISION -> ProcessingStage.SENSITIVE_DETECTION;
            case REDACT -> ProcessingStage.REDACTION;
            default -> ProcessingStage.DOCUMENT_PROCESSING;
        };
    }

    /** The claim is no longer current: the transaction rolls back and nothing of the step counts. */
    static final class ClaimLost extends RuntimeException {
        ClaimLost() {
            super("PIPELINE_JOB_CLAIM_NOT_CURRENT", null, false, false);
        }
    }

    /** A step's use case failed in a known stage (the incident records that stage, as inline). */
    private static final class StepFailure extends RuntimeException {
        private final ProcessingStage stage;
        private final RuntimeException cause;

        StepFailure(ProcessingStage stage, RuntimeException cause) {
            super(cause.getClass().getSimpleName(), cause, false, false);
            this.stage = stage;
            this.cause = cause;
        }
    }
}
