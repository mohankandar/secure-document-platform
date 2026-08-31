package com.securedoc.platform.worker.processing;

import com.securedoc.platform.application.detection.command.DetectSensitiveDataCommand;
import com.securedoc.platform.application.detection.result.DetectSensitiveDataResult;
import com.securedoc.platform.application.extraction.command.ExtractDocumentTextCommand;
import com.securedoc.platform.application.extraction.result.ExtractDocumentTextResult;
import com.securedoc.platform.application.port.in.DetectSensitiveDataUseCase;
import com.securedoc.platform.application.port.in.ExtractDocumentTextUseCase;
import com.securedoc.platform.application.port.in.RedactDocumentUseCase;
import com.securedoc.platform.application.port.in.StartDocumentProcessingUseCase;
import com.securedoc.platform.application.port.in.StartPackageProcessingUseCase;
import com.securedoc.platform.application.processing.command.StartDocumentProcessingCommand;
import com.securedoc.platform.application.processing.command.StartPackageProcessingCommand;
import com.securedoc.platform.application.processing.result.ProcessingWorkItem;
import com.securedoc.platform.application.processing.result.StartDocumentProcessingResult;
import com.securedoc.platform.application.processing.result.StartPackageProcessingResult;
import com.securedoc.platform.application.redaction.command.RedactDocumentCommand;
import com.securedoc.platform.application.redaction.result.RedactDocumentResult;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.policy.PolicyDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Coordinates the processing of submitted packages and their documents.
 * Called by the ProcessingWorker on each poll cycle.
 *
 * <p>This coordinator calls application services through their use case ports.
 * It does NOT bypass the application layer or directly manipulate JPA entities.
 *
 * <p>Flow per document:
 * <ol>
 *   <li>Start document processing (UPLOADED → EXTRACTING, acquires lock)</li>
 *   <li>Extract text from document (reads PDF, stores extracted text artifact)</li>
 * </ol>
 *
 * <p>No OCR, AI, or redaction implementation logic is performed here. Native/OCR
 * normalization, AI discovery, and redaction are delegated through application ports.
 */
public class ProcessingWorkCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ProcessingWorkCoordinator.class);

    private final StartPackageProcessingUseCase startPackageProcessingUseCase;
    private final StartDocumentProcessingUseCase startDocumentProcessingUseCase;
    private final ExtractDocumentTextUseCase extractDocumentTextUseCase;
    private final DetectSensitiveDataUseCase detectSensitiveDataUseCase;
    private final RedactDocumentUseCase redactDocumentUseCase;

    public ProcessingWorkCoordinator(StartPackageProcessingUseCase startPackageProcessingUseCase,
                                      StartDocumentProcessingUseCase startDocumentProcessingUseCase,
                                      ExtractDocumentTextUseCase extractDocumentTextUseCase,
                                      DetectSensitiveDataUseCase detectSensitiveDataUseCase,
                                      RedactDocumentUseCase redactDocumentUseCase) {
        this.startPackageProcessingUseCase = Objects.requireNonNull(startPackageProcessingUseCase);
        this.startDocumentProcessingUseCase = Objects.requireNonNull(startDocumentProcessingUseCase);
        this.extractDocumentTextUseCase = Objects.requireNonNull(extractDocumentTextUseCase);
        this.detectSensitiveDataUseCase = Objects.requireNonNull(detectSensitiveDataUseCase);
        this.redactDocumentUseCase = Objects.requireNonNull(redactDocumentUseCase);
    }

    /**
     * Processes a submitted package: transitions it to PROCESSING,
     * starts extraction on each document, and performs text extraction.
     *
     * @param packageId the package to process
     * @param tenantId  the owning tenant
     */
    public void processPackage(PackageId packageId, TenantId tenantId) {
        CorrelationId correlationId = CorrelationId.generate();

        log.info("Starting processing for package={} tenant={} correlation={}",
                packageId, tenantId, correlationId);

        // 1. Start package processing (SUBMITTED → PROCESSING)
        StartPackageProcessingCommand packageCommand = StartPackageProcessingCommand.of(
                packageId, tenantId, correlationId);
        StartPackageProcessingResult result = startPackageProcessingUseCase.execute(packageCommand);

        log.info("Package {} transitioned to {}. {} document(s) ready for extraction.",
                packageId, result.status(), result.documentCount());

        // 2. For each document: start processing then extract text
        for (ProcessingWorkItem workItem : result.workItems()) {
            processDocument(workItem, packageId, correlationId);
        }

        log.info("Processing coordination complete for package={}", packageId);
    }

    private void processDocument(ProcessingWorkItem workItem, PackageId packageId,
                                  CorrelationId correlationId) {
        try {
            // 2a. Start document processing (UPLOADED → EXTRACTING, acquires lock)
            StartDocumentProcessingCommand docCommand = StartDocumentProcessingCommand.of(
                    workItem.documentId(), workItem.packageId(), workItem.tenantId(), correlationId);
            StartDocumentProcessingResult docResult = startDocumentProcessingUseCase.execute(docCommand);

            log.debug("Started extraction for document={} in package={}",
                    workItem.documentId(), packageId);

            // 2b. Extract text (reads PDF, stores text artifact, completes extraction, releases lock)
            ExtractDocumentTextCommand extractCommand = ExtractDocumentTextCommand.of(
                    workItem.documentId(), workItem.packageId(), workItem.tenantId(),
                    docResult.lockKey(), docResult.lockToken(), correlationId);
            ExtractDocumentTextResult extractResult = extractDocumentTextUseCase.execute(extractCommand);

            log.info("Text normalization complete for document={}: pages={}, chars={}, hasText={}, embeddedText={}, usedOcr={}",
                    workItem.documentId(), extractResult.pageCount(),
                    extractResult.characterCount(), extractResult.hasText(),
                    extractResult.hasEmbeddedText(), extractResult.usedOcr());

            // 2c. Run sensitive-data detection. Empty/blank pages safely produce no findings.
            DetectSensitiveDataCommand detectCommand = DetectSensitiveDataCommand.of(
                    workItem.documentId(), workItem.packageId(), workItem.tenantId(), correlationId);
            DetectSensitiveDataResult detectResult = detectSensitiveDataUseCase.execute(detectCommand);

            log.info("Detection complete for document={}: findings={}, requiresRedaction={}, decision={}",
                    workItem.documentId(), detectResult.findingCount(),
                    detectResult.requiresRedaction(), detectResult.overallDecision());

            // 2d. Redact document if detection moved it to REDACTING and no review remains.
            if (detectResult.requiresRedaction()
                    && detectResult.overallDecision() != PolicyDecision.REVIEW
                    && detectResult.resultingStatus() == DocumentStatus.REDACTING) {
                RedactDocumentCommand redactCommand = RedactDocumentCommand.of(
                        workItem.documentId(), workItem.packageId(), workItem.tenantId(), correlationId);
                RedactDocumentResult redactResult = redactDocumentUseCase.execute(redactCommand);

                log.info("Redaction complete for document={}: redactions={}, status={}",
                        workItem.documentId(), redactResult.redactionCount(),
                        redactResult.resultingStatus());
            }

        } catch (Exception e) {
            log.warn("Failed to process document={} in package={}. See audit/outbox for sanitized failure reason.",
                    workItem.documentId(), packageId);
            // Continue with other documents — individual failures don't block the batch.
            // Services handle failure (marks doc failed, releases lock, publishes event).
        }
    }
}
