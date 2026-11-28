package com.securedoc.platform.application.processing.service;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.intelligence.service.DocumentIntelligenceExecutionService;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.pipeline.ExportJobScheduler;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.ProcessingLockPort;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
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
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.processing.ProcessingStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The case-level steps the staged pipeline shares (task 0048): the document-intelligence snapshot, whether a document
 * still waits for a person, the case's completion (Review, final approval, or Ready for export with its export job),
 * failing a document, and rewinding documents a stopped run left half done.
 *
 * <p>Task 0121 (owner 2026-11-28): staged only. The inline run (one worker doing the whole case) and the export at the
 * end of processing are removed; the V1 rules they carried stay, in the staged gate and the export job:
 * <ul>
 *   <li>Low-confidence findings pause only the affected document in REDACTING; the case goes to Review.</li>
 *   <li>Final delivery is held until every document in the case is ready; it is always an export job.</li>
 *   <li>A failed document is a technical failure of the case, never a successful completion.</li>
 * </ul>
 */
public final class TriggerPackageProcessingService {

    private static final Logger log = LoggerFactory.getLogger(TriggerPackageProcessingService.class);
    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    private static final String DOCUMENT_PROCESSING_LOCK_PREFIX = "doc-processing:";

    private final PackageRepository packageRepository;
    private final DocumentRepository documentRepository;
    private final FindingRepository findingRepository;
    private final ArtifactRepository artifactRepository;
    private final ProcessingLockPort processingLockPort;
    private final PackageLifecycleService lifecycleService;
    private final DocumentIntelligenceExecutionService documentIntelligenceExecutionService;
    private final AuditService auditService;
    private final ProcessingIncidentService processingIncidentService;
    private final ExportJobScheduler exportJobScheduler;

    public TriggerPackageProcessingService(PackageRepository packageRepository,
                                           DocumentRepository documentRepository,
                                           FindingRepository findingRepository,
                                           ArtifactRepository artifactRepository,
                                           ProcessingLockPort processingLockPort,
                                           PackageLifecycleService lifecycleService,
                                           DocumentIntelligenceExecutionService documentIntelligenceExecutionService,
                                           AuditService auditService,
                                           ProcessingIncidentService processingIncidentService,
                                           ExportJobScheduler exportJobScheduler) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.findingRepository = Objects.requireNonNull(findingRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.processingLockPort = Objects.requireNonNull(processingLockPort);
        this.lifecycleService = Objects.requireNonNull(lifecycleService);
        this.documentIntelligenceExecutionService = Objects.requireNonNull(documentIntelligenceExecutionService);
        this.auditService = Objects.requireNonNull(auditService);
        this.processingIncidentService = Objects.requireNonNull(processingIncidentService);
        this.exportJobScheduler = Objects.requireNonNull(exportJobScheduler);
    }

    void ensureDocumentIntelligenceSnapshot(TriggerPackageProcessingCommand command) {
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

    /**
     * Rewinds the documents a stopped run left half done (reading, finding or redacting; or processed without its
     * redacted copy): their outputs and findings are removed and they start again from the kept original. Already
     * finished documents are kept. Task 0121: used for a case an older version was running inline when it stopped (it
     * has no stage jobs), before it is planned as jobs.
     *
     * @return how many documents were rewound
     */
    int rewindInterruptedDocuments(TriggerPackageProcessingCommand command) {
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

            // The run that held the document is gone, so its document lock is stale as well. Clear it before replay.
            processingLockPort.forceRelease(DOCUMENT_PROCESSING_LOCK_PREFIX + document.id().value());

            for (var artifact : artifacts) {
                if (artifact.artifactType() != ArtifactType.ORIGINAL
                        && artifact.status() != ArtifactStatus.DELETED) {
                    lifecycleService.deleteArtifact(artifact,
                            com.securedoc.platform.application.port.out.ErasurePort.Reason.rule("interrupted-processing"));
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
                throw new ValidationException(LvErrorCode.PRC_009, e.getMessage());
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
        }
        return recovered;
    }

    boolean hasDetectedFindings(DocumentId documentId, TenantId tenantId) {
        return findingRepository.findByDocumentIdAndTenantId(documentId, tenantId)
                .stream()
                .anyMatch(finding -> finding.status() == FindingStatus.DETECTED);
    }

    /**
     * The staged gate's case decision, inside the gate's own transaction (task 0052): a failed document fails the case;
     * a finding for a person sends it to Review; when every document is processed, it waits for final approval (if the
     * entity asks for it) or is Ready for export with its export job queued. Nothing is ever sent from here.
     */
    void completeStagedPackageIfReady(PackageId packageId, TenantId tenantId, CorrelationId correlationId) {
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

        // Task 0027: the case is Ready for export. It keeps its status (PROCESSING, or PENDING_REVIEW / IN_REVIEW after a
        // review; not failed, not completed) while its export jobs run; the EXPORT job completes it. Queuing the same run
        // twice adds nothing.
        try {
            exportJobScheduler.queueCaseExport(tenantId, packageId,
                    lifecycleService.nextExportCycle(packageId, tenantId), correlationId);
        } catch (Exception e) {
            processingIncidentService.recordFailure(tenantId, packageId, null, ProcessingStage.DELIVERY,
                    e, correlationId, "Package export could not be queued", java.util.Map.of());
            failPackage(pkg, tenantId, packageId, correlationId,
                    TechnicalFailureClassifier.safeAuditDetail(
                            e, "Package export could not be queued; technical attention required"));
        }
    }

    void markDocumentFailedIfNeeded(DocumentId documentId, TenantId tenantId,
                                    CorrelationId correlationId, String safeReason) {
        Document document = documentRepository.findByIdAndTenantId(documentId, tenantId).orElse(null);
        if (document == null || document.status() == DocumentStatus.FAILED) {
            return;
        }
        document.fail();
        documentRepository.save(document);
        auditService.recordFailure(
                tenantId, SYSTEM_ACTOR, AuditAction.DOCUMENT_PROCESSING_FAILED,
                "Document", documentId.toString(), document.packageId(), correlationId, safeReason);
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
