package com.securedoc.platform.application.processing.service;

import com.securedoc.platform.application.detection.command.DetectSensitiveDataCommand;
import com.securedoc.platform.application.detection.result.DetectSensitiveDataResult;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.export.command.ExportPackageDocumentsCommand;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.licensing.TenantLicensingService;
import com.securedoc.platform.application.intelligence.service.DocumentIntelligenceExecutionService;
import com.securedoc.platform.application.extraction.command.ExtractDocumentTextCommand;
import com.securedoc.platform.application.extraction.result.ExtractDocumentTextResult;
import com.securedoc.platform.application.port.in.DetectSensitiveDataUseCase;
import com.securedoc.platform.application.port.in.ExportPackageDocumentsUseCase;
import com.securedoc.platform.application.port.in.ProcessDerivedRoutingUseCase;
import com.securedoc.platform.application.port.in.ExtractDocumentTextUseCase;
import com.securedoc.platform.application.port.in.RedactDocumentUseCase;
import com.securedoc.platform.application.port.in.StartDocumentProcessingUseCase;
import com.securedoc.platform.application.port.in.StartPackageProcessingUseCase;
import com.securedoc.platform.application.port.in.TriggerPackageProcessingUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.ProcessingLockPort;
import com.securedoc.platform.application.processing.command.StartDocumentProcessingCommand;
import com.securedoc.platform.application.processing.command.StartPackageProcessingCommand;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.processing.result.ProcessingWorkItem;
import com.securedoc.platform.application.processing.result.StartDocumentProcessingResult;
import com.securedoc.platform.application.processing.result.StartPackageProcessingResult;
import com.securedoc.platform.application.redaction.command.RedactDocumentCommand;
import com.securedoc.platform.application.resilience.SelectiveRetryExecutor;
import com.securedoc.platform.application.resilience.TechnicalFailureClassifier;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.technical.service.ProcessingIncidentService;
import com.securedoc.platform.domain.artifact.ArtifactStatus;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.policy.PolicyDecision;
import com.securedoc.platform.domain.processing.ProcessingStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Local/demo package orchestrator for the automatic processing path.
 *
 * <p>Important V1 semantics:
 * <ul>
 *   <li>AI/detection and redaction are automatic when no finding needs review.</li>
 *   <li>Low-confidence findings pause only the affected document in REDACTING.</li>
 *   <li>Final delivery is held until every document in the submitted package is ready.</li>
 *   <li>Delivery failure is a technical failure, never a successful completion.</li>
 *   <li>Successful straight-through delivery ends in COMPLETED, not PENDING_REVIEW.</li>
 * </ul>
 */
public final class TriggerPackageProcessingService implements TriggerPackageProcessingUseCase {

    private static final Logger log = LoggerFactory.getLogger(TriggerPackageProcessingService.class);
    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    private static final String DOCUMENT_PROCESSING_LOCK_PREFIX = "doc-processing:";

    private final PackageRepository packageRepository;
    private final DocumentRepository documentRepository;
    private final FindingRepository findingRepository;
    private final ArtifactRepository artifactRepository;
    private final ProcessingLockPort processingLockPort;
    private final StartPackageProcessingUseCase startPackageProcessingUseCase;
    private final StartDocumentProcessingUseCase startDocumentProcessingUseCase;
    private final ExtractDocumentTextUseCase extractDocumentTextUseCase;
    private final DetectSensitiveDataUseCase detectSensitiveDataUseCase;
    private final RedactDocumentUseCase redactDocumentUseCase;
    private final ExportPackageDocumentsUseCase exportPackageDocumentsUseCase;
    private final ProcessDerivedRoutingUseCase processDerivedRoutingUseCase;
    private final SelectiveRetryExecutor selectiveRetryExecutor;
    private final PackageLifecycleService lifecycleService;
    private final DocumentIntelligenceExecutionService documentIntelligenceExecutionService;
    private final AuditService auditService;
    private final ProcessingIncidentService processingIncidentService;
    private final TenantLicensingService tenantLicensingService;

    public TriggerPackageProcessingService(PackageRepository packageRepository,
                                           DocumentRepository documentRepository,
                                           FindingRepository findingRepository,
                                           ArtifactRepository artifactRepository,
                                           ProcessingLockPort processingLockPort,
                                           StartPackageProcessingUseCase startPackageProcessingUseCase,
                                           StartDocumentProcessingUseCase startDocumentProcessingUseCase,
                                           ExtractDocumentTextUseCase extractDocumentTextUseCase,
                                           DetectSensitiveDataUseCase detectSensitiveDataUseCase,
                                           RedactDocumentUseCase redactDocumentUseCase,
                                           ExportPackageDocumentsUseCase exportPackageDocumentsUseCase,
                                           ProcessDerivedRoutingUseCase processDerivedRoutingUseCase,
                                           SelectiveRetryExecutor selectiveRetryExecutor,
                                           PackageLifecycleService lifecycleService,
                                           DocumentIntelligenceExecutionService documentIntelligenceExecutionService,
                                           AuditService auditService,
                                           ProcessingIncidentService processingIncidentService,
                                           TenantLicensingService tenantLicensingService) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.findingRepository = Objects.requireNonNull(findingRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.processingLockPort = Objects.requireNonNull(processingLockPort);
        this.startPackageProcessingUseCase = Objects.requireNonNull(startPackageProcessingUseCase);
        this.startDocumentProcessingUseCase = Objects.requireNonNull(startDocumentProcessingUseCase);
        this.extractDocumentTextUseCase = Objects.requireNonNull(extractDocumentTextUseCase);
        this.detectSensitiveDataUseCase = Objects.requireNonNull(detectSensitiveDataUseCase);
        this.redactDocumentUseCase = Objects.requireNonNull(redactDocumentUseCase);
        this.exportPackageDocumentsUseCase = Objects.requireNonNull(exportPackageDocumentsUseCase);
        this.processDerivedRoutingUseCase = Objects.requireNonNull(processDerivedRoutingUseCase);
        this.selectiveRetryExecutor = Objects.requireNonNull(selectiveRetryExecutor);
        this.lifecycleService = Objects.requireNonNull(lifecycleService);
        this.documentIntelligenceExecutionService = Objects.requireNonNull(documentIntelligenceExecutionService);
        this.auditService = Objects.requireNonNull(auditService);
        this.processingIncidentService = Objects.requireNonNull(processingIncidentService);
        this.tenantLicensingService = Objects.requireNonNull(tenantLicensingService);
    }

    @Override
    public void execute(TriggerPackageProcessingCommand command) {
        Objects.requireNonNull(command, "Command must not be null");
        tenantLicensingService.requireDocumentProcessing(command.tenantId(), command.actorId(), command.correlationId());
        DocumentPackage pkg = packageRepository
                .findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));

        long startedNanos = System.nanoTime();
        log.info("PACKAGE_PROCESSING_STARTED package={} tenant={} status={} correlation={} recovery={}",
                command.packageId(), command.tenantId(), pkg.status(), command.correlationId(), command.recovery());
        try {
            if (command.recovery() && pkg.status() == PackageStatus.PROCESSING) {
                recoverInterruptedDocuments(command);
            }
            switch (pkg.status()) {
                case SUBMITTED -> {
                    ensureDocumentIntelligenceSnapshot(command);
                    executeFullProcessing(command);
                }
                case PROCESSING -> {
                    ensureDocumentIntelligenceSnapshot(command);
                    executeContinueProcessing(command);
                }
                case PENDING_REVIEW, IN_REVIEW -> {
                    ensureDocumentIntelligenceSnapshot(command);
                    executeContinueAfterReview(command);
                }
                default -> throw new ValidationException(
                        "Cannot trigger processing for package in status: " + pkg.status());
            }
        } finally {
            PackageStatus finalStatus = null;
            try {
                finalStatus = packageRepository.findByIdAndTenantId(command.packageId(), command.tenantId())
                        .map(DocumentPackage::status).orElse(null);
            } catch (RuntimeException ignored) {
                // Observability must never mask the real processing result/failure.
            }
            log.info("PACKAGE_PROCESSING_FINISHED package={} status={} durationMs={}",
                    command.packageId(), finalStatus, elapsedMillis(startedNanos));
        }
    }

    private void ensureDocumentIntelligenceSnapshot(TriggerPackageProcessingCommand command) {
        try {
            int processingCycle = lifecycleService.nextExportCycle(command.packageId(), command.tenantId());
            documentIntelligenceExecutionService.ensureSnapshot(
                    command.tenantId(), command.packageId(), processingCycle, command.correlationId());
        } catch (RuntimeException intelligenceSnapshotFailure) {
            // Document Intelligence is optional. Snapshot/configuration telemetry must never
            // take the frozen baseline processing path down.
            log.warn("DOCUMENT_INTELLIGENCE_CONTEXT_CAPTURE_FAILED package={} tenant={} correlation={} type={}",
                    command.packageId(), command.tenantId(), command.correlationId(),
                    intelligenceSnapshotFailure.getClass().getSimpleName());
        }
    }

    private void executeFullProcessing(TriggerPackageProcessingCommand command) {
        CorrelationId correlationId = command.correlationId();
        StartPackageProcessingResult result = startPackageProcessingUseCase.execute(
                StartPackageProcessingCommand.of(command.packageId(), command.tenantId(), correlationId));

        for (ProcessingWorkItem workItem : result.workItems()) {
            processDocument(workItem, correlationId);
        }
        completePackageProcessingIfReady(command.packageId(), command.tenantId(), correlationId);
    }

    private void executeContinueProcessing(TriggerPackageProcessingCommand command) {
        List<Document> documents = documentRepository
                .findByPackageIdAndTenantId(command.packageId(), command.tenantId());
        for (Document document : documents) {
            if (document.status() == DocumentStatus.UPLOADED) {
                processDocument(ProcessingWorkItem.of(
                        document.id(), command.packageId(), command.tenantId(), document.status()),
                        command.correlationId());
            } else if (document.status() == DocumentStatus.REDACTING
                    && !hasDetectedFindings(document.id(), command.tenantId())) {
                attemptRedaction(document, command.packageId(), command.tenantId(), command.correlationId());
            }
        }
        completePackageProcessingIfReady(command.packageId(), command.tenantId(), command.correlationId());
    }

    /**
     * Rewinds only interrupted document states after the prior distributed processing lease has
     * expired. Outputs/findings from the interrupted attempt are removed and the retained ORIGINAL
     * is replayed. Already PROCESSED documents are preserved.
     */
    private void recoverInterruptedDocuments(TriggerPackageProcessingCommand command) {
        List<Document> documents = documentRepository
                .findByPackageIdAndTenantId(command.packageId(), command.tenantId());
        int recovered = 0;

        for (Document document : documents) {
            var artifacts = artifactRepository.findByDocumentIdAndTenantId(document.id(), command.tenantId());
            boolean activeStage = document.status() == DocumentStatus.EXTRACTING
                    || document.status() == DocumentStatus.DETECTING
                    || document.status() == DocumentStatus.REDACTING;
            boolean missingProcessedOutput = document.status() == DocumentStatus.PROCESSED
                    && artifacts.stream().noneMatch(artifact -> artifact.artifactType() == ArtifactType.REDACTED
                    && artifact.status() == ArtifactStatus.AVAILABLE);
            if (!activeStage && !missingProcessedOutput) {
                continue;
            }

            // The package lease is already stale before a recovery command is dispatched, so any
            // document lock owned by the dead process is stale as well. Clear it before replay.
            processingLockPort.forceRelease(DOCUMENT_PROCESSING_LOCK_PREFIX + document.id().value());

            for (var artifact : artifacts) {
                if (artifact.artifactType() != ArtifactType.ORIGINAL
                        && artifact.status() != ArtifactStatus.DELETED) {
                    lifecycleService.deleteArtifact(artifact);
                }
            }
            findingRepository.deleteByDocumentIdAndTenantId(document.id(), command.tenantId());

            try {
                if (missingProcessedOutput) {
                    document.recoverMissingProcessedOutput();
                } else {
                    document.recoverInterruptedProcessing();
                }
            } catch (IllegalStateException e) {
                throw new ValidationException(e.getMessage());
            }
            documentRepository.save(document);
            recovered++;
        }

        if (recovered > 0) {
            auditService.recordSuccess(
                    command.tenantId(), SYSTEM_ACTOR, AuditAction.PACKAGE_PROCESSING_RECOVERED,
                    "DocumentPackage", command.packageId().toString(), command.correlationId(),
                    "Recovered interrupted package processing: documentsReplayed=" + recovered);
            log.info("PACKAGE_PROCESSING_RECOVERY_PREPARED package={} tenant={} documentsReplayed={}",
                    command.packageId(), command.tenantId(), recovered);
        } else {
            log.info("PACKAGE_PROCESSING_RECOVERY_CONTINUE package={} tenant={} documentsReplayed=0",
                    command.packageId(), command.tenantId());
        }
    }

    private void executeContinueAfterReview(TriggerPackageProcessingCommand command) {
        List<Document> redactingDocuments = documentRepository
                .findByPackageIdAndTenantId(command.packageId(), command.tenantId())
                .stream()
                .filter(document -> document.status() == DocumentStatus.REDACTING)
                .toList();

        if (redactingDocuments.isEmpty()) {
            throw new ValidationException("No documents in REDACTING status require processing");
        }

        for (Document document : redactingDocuments) {
            if (hasDetectedFindings(document.id(), command.tenantId())) {
                throw new ValidationException("Cannot continue redaction: document " + document.id()
                        + " still has findings requiring review");
            }
            attemptRedaction(document, command.packageId(), command.tenantId(), command.correlationId());
        }

        completePackageProcessingIfReady(command.packageId(), command.tenantId(), command.correlationId());
    }

    private void processDocument(ProcessingWorkItem workItem, CorrelationId correlationId) {
        ProcessingStage stage = ProcessingStage.DOCUMENT_PROCESSING;
        try {
            StartDocumentProcessingResult start = startDocumentProcessingUseCase.execute(
                    StartDocumentProcessingCommand.of(
                            workItem.documentId(), workItem.packageId(), workItem.tenantId(), correlationId));

            stage = ProcessingStage.TEXT_EXTRACTION;
            ExtractDocumentTextResult extraction = extractDocumentTextUseCase.execute(
                    ExtractDocumentTextCommand.of(
                            workItem.documentId(), workItem.packageId(), workItem.tenantId(),
                            start.lockKey(), start.lockToken(), correlationId));

            stage = ProcessingStage.SENSITIVE_DETECTION;
            DetectSensitiveDataResult detection = detectSensitiveDataUseCase.execute(
                    DetectSensitiveDataCommand.of(
                            workItem.documentId(), workItem.packageId(), workItem.tenantId(), correlationId));

            if (detection.resultingStatus() == DocumentStatus.REDACTING
                    && detection.overallDecision() != PolicyDecision.REVIEW) {
                attemptRedactionById(workItem.documentId(), workItem.packageId(), workItem.tenantId(), correlationId);
            }
            // REVIEW intentionally remains REDACTING until only confirmed/dismissed findings remain.
        } catch (Exception e) {
            processingIncidentService.recordFailure(workItem.tenantId(), workItem.packageId(), workItem.documentId(),
                    stage, e, correlationId, "Document processing failed", java.util.Map.of());
            markDocumentFailedIfNeeded(workItem.documentId(), workItem.tenantId(), correlationId,
                    TechnicalFailureClassifier.safeAuditDetail(e, "Document processing failed"));
        }
    }

    private void attemptRedaction(Document document, PackageId packageId,
                                  TenantId tenantId, CorrelationId correlationId) {
        attemptRedactionById(document.id(), packageId, tenantId, correlationId);
    }

    private void attemptRedactionById(DocumentId documentId, PackageId packageId,
                                      TenantId tenantId, CorrelationId correlationId) {
        try {
            redactDocumentUseCase.execute(
                    RedactDocumentCommand.of(documentId, packageId, tenantId, correlationId));
        } catch (ValidationException e) {
            // A review precondition is not a technical failure; surface it to the caller.
            throw e;
        } catch (Exception e) {
            processingIncidentService.recordFailure(tenantId, packageId, documentId, ProcessingStage.REDACTION,
                    e, correlationId, "Document redaction failed", java.util.Map.of());
            markDocumentFailedIfNeeded(documentId, tenantId, correlationId,
                    TechnicalFailureClassifier.safeAuditDetail(e, "Document redaction failed"));
        }
    }

    private boolean hasDetectedFindings(DocumentId documentId, TenantId tenantId) {
        return findingRepository.findByDocumentIdAndTenantId(documentId, tenantId)
                .stream()
                .anyMatch(finding -> finding.status() == FindingStatus.DETECTED);
    }

    /**
     * Resolves review/technical/success package state and performs final batch-gated delivery.
     * Delivery is attempted only after every document is PROCESSED and no finding requires review.
     */
    private void completePackageProcessingIfReady(PackageId packageId, TenantId tenantId,
                                                  CorrelationId correlationId) {
        DocumentPackage pkg = packageRepository.findByIdAndTenantId(packageId, tenantId).orElse(null);
        if (pkg == null || (pkg.status() != PackageStatus.PROCESSING
                && pkg.status() != PackageStatus.PENDING_REVIEW
                && pkg.status() != PackageStatus.IN_REVIEW)) {
            return;
        }

        List<Document> documents = documentRepository.findByPackageIdAndTenantId(packageId, tenantId);
        if (documents.isEmpty()) {
            return;
        }

        boolean anyWorking = documents.stream().anyMatch(document ->
                document.status() == DocumentStatus.UPLOADED
                        || document.status() == DocumentStatus.EXTRACTING
                        || document.status() == DocumentStatus.DETECTING);
        if (anyWorking) {
            return;
        }

        boolean anyFailed = documents.stream().anyMatch(document -> document.status() == DocumentStatus.FAILED);
        if (anyFailed) {
            failPackage(pkg, tenantId, packageId, correlationId,
                    "Package requires technical attention: one or more documents failed");
            return;
        }

        boolean requiresReview = documents.stream().anyMatch(document -> hasDetectedFindings(document.id(), tenantId));
        if (requiresReview) {
            if (pkg.status() == PackageStatus.PROCESSING) {
                pkg.completeProcessing(); // PROCESSING -> PENDING_REVIEW only for a real review condition
                packageRepository.save(pkg);
                auditService.recordSuccess(
                        tenantId, SYSTEM_ACTOR, AuditAction.PACKAGE_PROCESSING_COMPLETED,
                        "DocumentPackage", packageId.toString(), correlationId,
                        "Package processing paused: manual review required");
            }
            return;
        }

        boolean anyAwaitingRedaction = documents.stream()
                .anyMatch(document -> document.status() == DocumentStatus.REDACTING);
        if (anyAwaitingRedaction) {
            return;
        }

        boolean allProcessed = documents.stream()
                .allMatch(document -> document.status() == DocumentStatus.PROCESSED);
        if (!allProcessed) {
            return;
        }

        // Optional trust-building mode: all document work is complete, but customer delivery
        // is held until an APPROVER explicitly releases this export cycle.
        if (lifecycleService.requiresFinalApproval(packageId, tenantId)) {
            if (pkg.status() != PackageStatus.PENDING_FINAL_APPROVAL) {
                pkg.holdForFinalApproval();
                packageRepository.save(pkg);
                auditService.recordSuccess(
                        tenantId, SYSTEM_ACTOR, AuditAction.PACKAGE_FINAL_APPROVAL_REQUIRED,
                        "DocumentPackage", packageId.toString(), correlationId,
                        "Package ready for delivery; final approval required");
            }
            return;
        }

        // Batch gate: the destination receives the complete package atomically. No individual
        // document becomes visible until every staged document has been written successfully.
        com.securedoc.platform.application.export.result.ExportPackageDocumentsResult exportResult;
        try {
            exportResult = selectiveRetryExecutor.execute(() -> exportPackageDocumentsUseCase.execute(
                    ExportPackageDocumentsCommand.of(packageId, tenantId, correlationId)));
        } catch (Exception e) {
            processingIncidentService.recordFailure(tenantId, packageId, null, ProcessingStage.DELIVERY,
                    e, correlationId, "Package delivery failed", java.util.Map.of());
            failPackage(pkg, tenantId, packageId, correlationId,
                    TechnicalFailureClassifier.safeAuditDetail(
                            e, "Package delivery failed; technical attention required"));
            return;
        }

        // Option A: master SDV/local export is authoritative for package completion. Derived
        // routing is an independent post-export track; failures are recorded there and never
        // undo the successful master export or its retention semantics.
        try {
            processDerivedRoutingUseCase.processPackage(packageId, tenantId, exportResult.exportCycle(), correlationId);
        } catch (Exception e) {
            log.warn("DERIVED_ROUTING_POST_EXPORT_FAILED package={} tenant={} correlation={} type={}",
                    packageId, tenantId, correlationId, e.getClass().getSimpleName());
        }

        pkg.completeSuccessfully();
        packageRepository.save(pkg);
        processingIncidentService.markProcessingCompleted(packageId, tenantId, correlationId);
        auditService.recordSuccess(
                tenantId, SYSTEM_ACTOR, AuditAction.PACKAGE_PROCESSING_COMPLETED,
                "DocumentPackage", packageId.toString(), correlationId,
                "Package processing and delivery completed: " + documents.size() + " document(s)");
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private void markDocumentFailedIfNeeded(DocumentId documentId, TenantId tenantId,
                                            CorrelationId correlationId, String safeReason) {
        Document document = documentRepository.findByIdAndTenantId(documentId, tenantId).orElse(null);
        if (document == null || document.status() == DocumentStatus.FAILED) {
            return;
        }
        document.fail();
        documentRepository.save(document);
        auditService.recordFailure(
                tenantId, SYSTEM_ACTOR, AuditAction.DOCUMENT_PROCESSING_FAILED,
                "Document", documentId.toString(), correlationId, safeReason);
    }

    private void markDocumentFailed(DocumentId documentId, TenantId tenantId,
                                    CorrelationId correlationId, String safeReason) {
        markDocumentFailedIfNeeded(documentId, tenantId, correlationId, safeReason);
    }

    private void failPackage(DocumentPackage pkg, TenantId tenantId, PackageId packageId,
                             CorrelationId correlationId, String safeReason) {
        if (pkg.status() != PackageStatus.FAILED) {
            pkg.fail();
            packageRepository.save(pkg);
        }
        auditService.recordFailure(
                tenantId, SYSTEM_ACTOR, AuditAction.DOCUMENT_PROCESSING_FAILED,
                "DocumentPackage", packageId.toString(), correlationId, safeReason);
        try {
            lifecycleService.recordTechnicalFailure(packageId, tenantId, correlationId);
        } catch (Exception ignored) {
            // Failure classification/state remains authoritative even if lifecycle scheduling itself fails.
        }
    }
}
