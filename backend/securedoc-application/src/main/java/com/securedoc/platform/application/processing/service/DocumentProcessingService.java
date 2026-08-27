package com.securedoc.platform.application.processing.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.in.FailDocumentProcessingUseCase;
import com.securedoc.platform.application.port.in.StartDocumentProcessingUseCase;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.ProcessingLockPort;
import com.securedoc.platform.application.processing.command.FailDocumentProcessingCommand;
import com.securedoc.platform.application.processing.command.StartDocumentProcessingCommand;
import com.securedoc.platform.application.processing.result.StartDocumentProcessingResult;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.event.DocumentProcessingFailedEvent;
import com.securedoc.platform.domain.event.DocumentProcessingStartedEvent;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Application service implementing document-level processing orchestration.
 * Acquires a processing lock before transitioning a document to EXTRACTING,
 * and handles failure marking with proper event publishing.
 *
 * <p>This service is invoked by the worker layer (system-initiated).
 * No actual OCR/AI/redaction work is performed here — this is the coordination
 * layer that manages state transitions and concurrency safety.
 */
public final class DocumentProcessingService implements StartDocumentProcessingUseCase, FailDocumentProcessingUseCase {

    /** System actor used for audit records in worker-initiated operations. */
    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));

    /** Default lock TTL for document processing. */
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    /** Lock key prefix for document processing. */
    private static final String LOCK_PREFIX = "doc-processing:";

    private final DocumentRepository documentRepository;
    private final PackageRepository packageRepository;
    private final ProcessingLockPort processingLockPort;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public DocumentProcessingService(DocumentRepository documentRepository,
                                      PackageRepository packageRepository,
                                      ProcessingLockPort processingLockPort,
                                      DomainEventPublisher eventPublisher,
                                      AuditService auditService) {
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.processingLockPort = Objects.requireNonNull(processingLockPort);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public StartDocumentProcessingResult execute(StartDocumentProcessingCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        String lockKey = LOCK_PREFIX + command.documentId().value();
        CorrelationId correlationId = command.correlationId();

        // 1. Acquire processing lock to prevent concurrent processing
        String lockToken = processingLockPort.tryAcquire(lockKey, LOCK_TTL)
                .orElseThrow(() -> new ValidationException(
                        "Document is already being processed"));

        try {
            // 2. Load document and validate ownership
            Document document = documentRepository
                    .findByIdAndTenantId(command.documentId(), command.tenantId())
                    .orElseThrow(() -> new NotFoundException("Document", command.documentId().toString()));

            // 3. Validate document belongs to the specified package
            if (!document.packageId().equals(command.packageId())) {
                throw new ValidationException("Document does not belong to the specified package");
            }

            // 4. Load parent package and validate it is in PROCESSING status
            DocumentPackage parentPackage = packageRepository
                    .findByIdAndTenantId(command.packageId(), command.tenantId())
                    .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));

            if (parentPackage.status() != PackageStatus.PROCESSING) {
                throw new ValidationException("Package is not in PROCESSING status");
            }

            // 5. Transition to EXTRACTING via domain lifecycle (validates UPLOADED state)
            document.startExtraction();

            // 6. Persist
            documentRepository.save(document);

            // 7. Publish domain event
            DocumentProcessingStartedEvent event = DocumentProcessingStartedEvent.of(
                    command.documentId(), command.packageId(), command.tenantId(), correlationId);
            eventPublisher.publish(event);

            // 8. Audit
            auditService.recordSuccess(
                    command.tenantId(),
                    SYSTEM_ACTOR,
                    AuditAction.DOCUMENT_EXTRACTION_STARTED,
                    "Document",
                    command.documentId().toString(),
                    correlationId,
                    "Document extraction started"
            );

            // 9. Return result with lock info for caller to manage
            return StartDocumentProcessingResult.of(
                    command.documentId(), command.packageId(), command.tenantId(),
                    document.status(), lockKey, lockToken);

        } catch (Exception e) {
            // Release lock on failure so another worker can retry
            processingLockPort.release(lockKey, lockToken);
            throw e;
        }
    }

    @Override
    public void execute(FailDocumentProcessingCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        String lockKey = LOCK_PREFIX + command.documentId().value();
        CorrelationId correlationId = command.correlationId();

        Document document;
        try {
            // 1. Load document by tenant and validate it belongs to the specified package.
            document = documentRepository
                    .findByIdAndTenantId(command.documentId(), command.tenantId())
                    .orElseThrow(() -> new NotFoundException("Document", command.documentId().toString()));

            if (!document.packageId().equals(command.packageId())) {
                throw new ValidationException("Document does not belong to the specified package");
            }

            // 2. Load parent package by tenant to confirm tenant ownership and package existence.
            packageRepository.findByIdAndTenantId(command.packageId(), command.tenantId())
                    .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));
        } catch (RuntimeException e) {
            releaseLockIfPresent(lockKey, command);
            throw e;
        }

        // 3. Sanitize the failure reason before storing.
        String sanitizedReason = FailureReasonSanitizer.sanitize(command.failureReason());

        // 4. Mark document as failed via domain lifecycle.
        document.fail();

        // 5. Persist.
        documentRepository.save(document);

        // 6. Release processing lock if a valid token was provided.
        releaseLockIfPresent(lockKey, command);

        // 7. Publish failure event (with sanitized reason).
        DocumentProcessingFailedEvent event = DocumentProcessingFailedEvent.of(
                command.documentId(), command.packageId(), command.tenantId(),
                sanitizedReason, correlationId);
        eventPublisher.publish(event);

        // 8. Audit (with sanitized reason).
        auditService.recordFailure(
                command.tenantId(),
                SYSTEM_ACTOR,
                AuditAction.DOCUMENT_PROCESSING_FAILED,
                "Document",
                command.documentId().toString(),
                correlationId,
                "Processing failed: " + sanitizedReason
        );

        // 9. Optionally fail the parent package.
        failPackageIfAllDocumentsFailed(command);
    }

    private void releaseLockIfPresent(String lockKey, FailDocumentProcessingCommand command) {
        if (command.hasLockToken()) {
            processingLockPort.release(lockKey, command.lockToken());
        }
    }

    /**
     * If all documents in the package have failed, mark the package as failed too.
     */
    private void failPackageIfAllDocumentsFailed(FailDocumentProcessingCommand command) {
        var documents = documentRepository.findByPackageIdAndTenantId(
                command.packageId(), command.tenantId());

        boolean allFailed = documents.stream()
                .allMatch(doc -> doc.status() == DocumentStatus.FAILED);

        if (allFailed && !documents.isEmpty()) {
            packageRepository.findByIdAndTenantId(command.packageId(), command.tenantId())
                    .ifPresent(pkg -> {
                        try {
                            pkg.fail();
                            packageRepository.save(pkg);
                        } catch (IllegalStateException ignored) {
                            // Package may already be in a terminal state
                        }
                    });
        }
    }
}
