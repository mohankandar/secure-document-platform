package com.securedoc.platform.application.processing.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.in.RedactDocumentUseCase;
import com.securedoc.platform.application.port.in.StartDocumentProcessingUseCase;
import com.securedoc.platform.application.port.in.StartPackageProcessingUseCase;
import com.securedoc.platform.application.port.in.TriggerPackageProcessingUseCase;
import com.securedoc.platform.application.port.in.ExtractDocumentTextUseCase;
import com.securedoc.platform.application.port.in.DetectSensitiveDataUseCase;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.detection.command.DetectSensitiveDataCommand;
import com.securedoc.platform.application.detection.result.DetectSensitiveDataResult;
import com.securedoc.platform.application.extraction.command.ExtractDocumentTextCommand;
import com.securedoc.platform.application.extraction.result.ExtractDocumentTextResult;
import com.securedoc.platform.application.processing.command.StartDocumentProcessingCommand;
import com.securedoc.platform.application.processing.command.StartPackageProcessingCommand;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.processing.result.ProcessingWorkItem;
import com.securedoc.platform.application.processing.result.StartDocumentProcessingResult;
import com.securedoc.platform.application.processing.result.StartPackageProcessingResult;
import com.securedoc.platform.application.redaction.command.RedactDocumentCommand;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Application service that triggers package processing for the local/demo flow.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li>SUBMITTED: full processing pipeline (start → extract → detect → redact)</li>
 *   <li>PENDING_REVIEW/IN_REVIEW: continue redaction for documents in REDACTING status
 *       when findings have bounding boxes</li>
 * </ul>
 *
 * <p>After processing, moves the package to PENDING_REVIEW if all documents
 * have reached a terminal state (PROCESSED or FAILED).
 */
public final class TriggerPackageProcessingService implements TriggerPackageProcessingUseCase {

    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));

    private final PackageRepository packageRepository;
    private final DocumentRepository documentRepository;
    private final FindingRepository findingRepository;
    private final StartPackageProcessingUseCase startPackageProcessingUseCase;
    private final StartDocumentProcessingUseCase startDocumentProcessingUseCase;
    private final ExtractDocumentTextUseCase extractDocumentTextUseCase;
    private final DetectSensitiveDataUseCase detectSensitiveDataUseCase;
    private final RedactDocumentUseCase redactDocumentUseCase;
    private final AuditService auditService;

    public TriggerPackageProcessingService(PackageRepository packageRepository,
                                            DocumentRepository documentRepository,
                                            FindingRepository findingRepository,
                                            StartPackageProcessingUseCase startPackageProcessingUseCase,
                                            StartDocumentProcessingUseCase startDocumentProcessingUseCase,
                                            ExtractDocumentTextUseCase extractDocumentTextUseCase,
                                            DetectSensitiveDataUseCase detectSensitiveDataUseCase,
                                            RedactDocumentUseCase redactDocumentUseCase,
                                            AuditService auditService) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.findingRepository = Objects.requireNonNull(findingRepository);
        this.startPackageProcessingUseCase = Objects.requireNonNull(startPackageProcessingUseCase);
        this.startDocumentProcessingUseCase = Objects.requireNonNull(startDocumentProcessingUseCase);
        this.extractDocumentTextUseCase = Objects.requireNonNull(extractDocumentTextUseCase);
        this.detectSensitiveDataUseCase = Objects.requireNonNull(detectSensitiveDataUseCase);
        this.redactDocumentUseCase = Objects.requireNonNull(redactDocumentUseCase);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public void execute(TriggerPackageProcessingCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        DocumentPackage pkg = packageRepository
                .findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));

        switch (pkg.status()) {
            case SUBMITTED -> executeFullProcessing(command);
            case PROCESSING -> executeContinueProcessing(command, pkg);
            case PENDING_REVIEW, IN_REVIEW -> executeContinueRedaction(command, pkg);
            default -> throw new ValidationException(
                    "Cannot trigger processing for package in status: " + pkg.status());
        }
    }

    /**
     * Full processing from SUBMITTED: start package, then process each document.
     */
    private void executeFullProcessing(TriggerPackageProcessingCommand command) {
        CorrelationId correlationId = command.correlationId();

        // 1. Start package processing (SUBMITTED → PROCESSING)
        StartPackageProcessingCommand pkgCommand = StartPackageProcessingCommand.of(
                command.packageId(), command.tenantId(), correlationId);
        StartPackageProcessingResult result = startPackageProcessingUseCase.execute(pkgCommand);

        // 2. Process each document
        for (ProcessingWorkItem workItem : result.workItems()) {
            processDocument(workItem, command.packageId(), command.tenantId(), correlationId);
        }

        // 3. Complete package processing if all documents are done
        completePackageProcessingIfReady(command.packageId(), command.tenantId(), correlationId);
    }

    /**
     * Continue processing for a package already in PROCESSING (e.g., partial completion).
     */
    private void executeContinueProcessing(TriggerPackageProcessingCommand command, DocumentPackage pkg) {
        CorrelationId correlationId = command.correlationId();

        List<Document> documents = documentRepository
                .findByPackageIdAndTenantId(command.packageId(), command.tenantId());

        for (Document doc : documents) {
            if (doc.status() == DocumentStatus.REDACTING) {
                attemptRedaction(doc, command.packageId(), command.tenantId(), correlationId);
            }
        }

        completePackageProcessingIfReady(command.packageId(), command.tenantId(), correlationId);
    }

    /**
     * Continue redaction for documents in REDACTING status after review has added bounding boxes.
     * Temporarily moves the package back to PROCESSING for the redaction service to accept it.
     */
    private void executeContinueRedaction(TriggerPackageProcessingCommand command, DocumentPackage pkg) {
        CorrelationId correlationId = command.correlationId();

        List<Document> documents = documentRepository
                .findByPackageIdAndTenantId(command.packageId(), command.tenantId());

        List<Document> redactingDocs = documents.stream()
                .filter(doc -> doc.status() == DocumentStatus.REDACTING)
                .toList();

        if (redactingDocs.isEmpty()) {
            throw new ValidationException("No documents in REDACTING status require processing");
        }

        // Validate all actionable findings have bounding boxes before proceeding
        for (Document doc : redactingDocs) {
            List<Finding> findings = findingRepository.findByDocumentIdAndTenantId(
                    doc.id(), command.tenantId());
            boolean hasUnreviewedFindings = findings.stream()
                    .anyMatch(f -> f.status() == FindingStatus.DETECTED);
            if (hasUnreviewedFindings) {
                throw new ValidationException("Cannot continue redaction: document " + doc.id()
                        + " has unreviewed DETECTED findings");
            }
            boolean actionableMissingBounds = findings.stream()
                    .filter(f -> f.status() == FindingStatus.CONFIRMED)
                    .anyMatch(f -> !f.location().hasBoundingBox());
            if (actionableMissingBounds) {
                throw new ValidationException("Cannot continue redaction: document " + doc.id()
                        + " has confirmed findings without bounding boxes");
            }
        }

        // Process each REDACTING document based on its finding state
        for (Document doc : redactingDocs) {
            List<Finding> findings = findingRepository.findByDocumentIdAndTenantId(
                    doc.id(), command.tenantId());

            boolean hasConfirmedFindings = findings.stream()
                    .anyMatch(f -> f.status() == FindingStatus.CONFIRMED);

            if (hasConfirmedFindings) {
                // Confirmed findings with bounding boxes exist — run redaction
                try {
                    RedactDocumentCommand redactCommand = RedactDocumentCommand.of(
                            doc.id(), command.packageId(), command.tenantId(), correlationId);
                    redactDocumentUseCase.execute(redactCommand);
                } catch (Exception e) {
                    auditService.recordFailure(command.tenantId(), SYSTEM_ACTOR,
                            AuditAction.DOCUMENT_PROCESSING_FAILED, "Document", doc.id().toString(),
                            correlationId, "Post-review redaction failed for document");
                }
            } else {
                // No confirmed findings remain — all were dismissed/overridden/already redacted.
                // Complete the document without redaction (no redacted artifact needed).
                doc.completeProcessing();
                documentRepository.save(doc);

                auditService.recordSuccess(command.tenantId(), SYSTEM_ACTOR,
                        AuditAction.DOCUMENT_REDACTION_COMPLETED, "Document", doc.id().toString(),
                        correlationId, "Document completed without redaction: all findings dismissed or resolved");
            }
        }

        // After processing attempts, check if we can complete package processing
        completePackageProcessingIfReady(command.packageId(), command.tenantId(), correlationId);
    }

    private void processDocument(ProcessingWorkItem workItem, com.securedoc.platform.domain.identity.PackageId packageId,
                                  com.securedoc.platform.domain.identity.TenantId tenantId, CorrelationId correlationId) {
        try {
            // Start document processing (UPLOADED → EXTRACTING)
            StartDocumentProcessingCommand docCommand = StartDocumentProcessingCommand.of(
                    workItem.documentId(), workItem.packageId(), workItem.tenantId(), correlationId);
            StartDocumentProcessingResult docResult = startDocumentProcessingUseCase.execute(docCommand);

            // Extract text
            ExtractDocumentTextCommand extractCommand = ExtractDocumentTextCommand.of(
                    workItem.documentId(), workItem.packageId(), workItem.tenantId(),
                    docResult.lockKey(), docResult.lockToken(), correlationId);
            ExtractDocumentTextResult extractResult = extractDocumentTextUseCase.execute(extractCommand);

            // Detect sensitive data (if text was extracted)
            if (extractResult.hasEmbeddedText()) {
                DetectSensitiveDataCommand detectCommand = DetectSensitiveDataCommand.of(
                        workItem.documentId(), workItem.packageId(), workItem.tenantId(), correlationId);
                DetectSensitiveDataResult detectResult = detectSensitiveDataUseCase.execute(detectCommand);

                // Redact only if detection moved to REDACTING AND all findings have bounding boxes
                if (detectResult.requiresRedaction()
                        && detectResult.resultingStatus() == DocumentStatus.REDACTING) {

                    // Check if all actionable findings have bounding boxes
                    List<Finding> findings = findingRepository.findByDocumentIdAndTenantId(
                            workItem.documentId(), workItem.tenantId());
                    boolean allHaveBounds = findings.stream()
                            .filter(f -> f.status() == FindingStatus.DETECTED || f.status() == FindingStatus.CONFIRMED)
                            .allMatch(f -> f.location().hasBoundingBox());

                    if (allHaveBounds) {
                        RedactDocumentCommand redactCommand = RedactDocumentCommand.of(
                                workItem.documentId(), workItem.packageId(), workItem.tenantId(), correlationId);
                        redactDocumentUseCase.execute(redactCommand);
                    }
                    // If not all have bounding boxes, leave document in REDACTING
                    // for human review to add bounding boxes later
                }
            }
        } catch (Exception e) {
            // Individual document failures don't block the batch
        }
    }

    private void attemptRedaction(Document doc, com.securedoc.platform.domain.identity.PackageId packageId,
                                   com.securedoc.platform.domain.identity.TenantId tenantId, CorrelationId correlationId) {
        try {
            RedactDocumentCommand redactCommand = RedactDocumentCommand.of(
                    doc.id(), packageId, tenantId, correlationId);
            redactDocumentUseCase.execute(redactCommand);
        } catch (Exception e) {
            // Individual document failures don't block the batch
        }
    }

    /**
     * After processing, moves the package from PROCESSING to PENDING_REVIEW
     * if all documents have reached a state where the next step is human review or finalization.
     * Valid states: PROCESSED, FAILED, or REDACTING (waiting for bounding boxes/review).
     * Does not transition if any document is still UPLOADED, EXTRACTING, or DETECTING.
     */
    private void completePackageProcessingIfReady(com.securedoc.platform.domain.identity.PackageId packageId,
                                                   com.securedoc.platform.domain.identity.TenantId tenantId,
                                                   CorrelationId correlationId) {
        DocumentPackage pkg = packageRepository
                .findByIdAndTenantId(packageId, tenantId)
                .orElse(null);
        if (pkg == null || pkg.status() != PackageStatus.PROCESSING) {
            return;
        }

        List<Document> documents = documentRepository.findByPackageIdAndTenantId(packageId, tenantId);
        boolean allReadyForNextStep = !documents.isEmpty() && documents.stream()
                .allMatch(doc -> doc.status() == DocumentStatus.PROCESSED
                        || doc.status() == DocumentStatus.FAILED
                        || doc.status() == DocumentStatus.REDACTING);

        if (allReadyForNextStep) {
            pkg.completeProcessing(); // PROCESSING → PENDING_REVIEW
            packageRepository.save(pkg);

            auditService.recordSuccess(tenantId, SYSTEM_ACTOR,
                    AuditAction.PACKAGE_PROCESSING_COMPLETED, "DocumentPackage", packageId.toString(),
                    correlationId,
                    "Package processing completed: " + documents.size() + " document(s)");
        }
    }
}
