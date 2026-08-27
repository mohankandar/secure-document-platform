package com.securedoc.platform.application.extraction.service;

import com.securedoc.platform.application.exception.ApplicationException;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.extraction.command.ExtractDocumentTextCommand;
import com.securedoc.platform.application.extraction.result.ExtractDocumentTextResult;
import com.securedoc.platform.application.port.in.ExtractDocumentTextUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.ProcessingLockPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.application.port.out.TextExtractionPort.PageText;
import com.securedoc.platform.application.port.out.TextExtractionPort.TextExtractionOutput;
import com.securedoc.platform.application.processing.service.FailureReasonSanitizer;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.Page;
import com.securedoc.platform.domain.event.TextExtractionCompletedEvent;
import com.securedoc.platform.domain.event.TextExtractionFailedEvent;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.MimeType;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Application service implementing text extraction from document artifacts.
 *
 * <p>Validation/precondition failures do not change document state. Only actual
 * extraction/storage/completion failures after preconditions pass mark the
 * document failed and publish extraction failure events.
 *
 * <p>Does NOT log or store extracted text in audit/outbox/events.
 */
public final class TextExtractionService implements ExtractDocumentTextUseCase {

    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    private static final String TEXT_MIME_TYPE = "text/plain";
    private static final String NO_EMBEDDED_TEXT_REASON = "No embedded text found; OCR provider required";

    private final DocumentRepository documentRepository;
    private final PackageRepository packageRepository;
    private final ArtifactRepository artifactRepository;
    private final StoragePort storagePort;
    private final TextExtractionPort textExtractionPort;
    private final ChecksumPort checksumPort;
    private final ProcessingLockPort processingLockPort;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public TextExtractionService(DocumentRepository documentRepository,
                                  PackageRepository packageRepository,
                                  ArtifactRepository artifactRepository,
                                  StoragePort storagePort,
                                  TextExtractionPort textExtractionPort,
                                  ChecksumPort checksumPort,
                                  ProcessingLockPort processingLockPort,
                                  DomainEventPublisher eventPublisher,
                                  AuditService auditService) {
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.storagePort = Objects.requireNonNull(storagePort);
        this.textExtractionPort = Objects.requireNonNull(textExtractionPort);
        this.checksumPort = Objects.requireNonNull(checksumPort);
        this.processingLockPort = Objects.requireNonNull(processingLockPort);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public ExtractDocumentTextResult execute(ExtractDocumentTextCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        ExtractionContext context;
        try {
            context = loadAndValidate(command);
        } catch (RuntimeException e) {
            releaseLockQuietly(command);
            throw e;
        }

        try {
            return extractAndComplete(command, context);
        } catch (RuntimeException e) {
            handleProcessingFailure(command, context.document(), e);
            throw e;
        }
    }

    private ExtractionContext loadAndValidate(ExtractDocumentTextCommand command) {
        Document document = documentRepository
                .findByIdAndTenantId(command.documentId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Document", command.documentId().toString()));

        if (!document.packageId().equals(command.packageId())) {
            throw new ValidationException("Document does not belong to the specified package");
        }

        DocumentPackage parentPackage = packageRepository
                .findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));

        if (parentPackage.status() != PackageStatus.PROCESSING) {
            throw new ValidationException("Package is not in PROCESSING status");
        }

        if (document.status() != DocumentStatus.EXTRACTING) {
            throw new ValidationException("Document is not in EXTRACTING status");
        }

        Artifact originalArtifact = artifactRepository
                .findByDocumentIdAndTenantId(document.id(), command.tenantId())
                .stream()
                .filter(a -> a.artifactType() == ArtifactType.ORIGINAL && a.isAvailable())
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Original artifact for document",
                        command.documentId().toString()));

        return new ExtractionContext(document, originalArtifact);
    }

    private ExtractDocumentTextResult extractAndComplete(ExtractDocumentTextCommand command,
                                                          ExtractionContext context) {
        Document document = context.document();

        TextExtractionOutput extractionOutput;
        try (InputStream pdfContent = storagePort.retrieve(context.originalArtifact().storageReference())) {
            extractionOutput = textExtractionPort.extract(pdfContent, document.mimeType().value());
        } catch (ApplicationException e) {
            throw e;
        } catch (Exception e) {
            throw new ExtractionProcessingException("Text extraction failed", e);
        }

        if (!extractionOutput.hasEmbeddedText() || extractionOutput.totalCharacterCount() == 0) {
            return handleNoEmbeddedText(command, document, extractionOutput);
        }

        List<Page> pages = extractionOutput.pages().stream()
                .map(pt -> pt.text().isBlank()
                        ? Page.of(pt.pageNumber(), pt.width(), pt.height())
                        : Page.withText(pt.pageNumber(), pt.width(), pt.height(), pt.text()))
                .toList();

        String fullText = extractionOutput.pages().stream()
                .map(PageText::text)
                .reduce((a, b) -> a + "\n\n--- Page Break ---\n\n" + b)
                .orElse("");

        byte[] textBytes = fullText.getBytes(StandardCharsets.UTF_8);
        Checksum textChecksum = checksumPort.compute(new ByteArrayInputStream(textBytes));

        Artifact textArtifact = Artifact.create(
                command.tenantId(),
                document.id(),
                ArtifactType.EXTRACTED_TEXT,
                MimeType.of(TEXT_MIME_TYPE),
                textBytes.length,
                textChecksum
        );

        String storagePath = buildStoragePath(
                command.tenantId().toString(),
                command.packageId().toString(),
                document.id().toString(),
                ArtifactType.EXTRACTED_TEXT.name(),
                textArtifact.id().toString()
        );

        ArtifactStorageReference storageRef = storagePort.store(
                command.tenantId(),
                storagePath,
                new ByteArrayInputStream(textBytes),
                textBytes.length,
                TEXT_MIME_TYPE
        );

        textArtifact.markAvailable(storageRef);
        document.completeExtraction(pages);

        documentRepository.save(document);
        artifactRepository.save(textArtifact);

        TextExtractionCompletedEvent event = TextExtractionCompletedEvent.of(
                command.documentId(), command.packageId(), command.tenantId(),
                textArtifact.id(), extractionOutput.pageCount(),
                extractionOutput.totalCharacterCount(), command.correlationId());
        eventPublisher.publish(event);

        auditService.recordSuccess(
                command.tenantId(),
                SYSTEM_ACTOR,
                AuditAction.DOCUMENT_EXTRACTION_COMPLETED,
                "Document",
                command.documentId().toString(),
                command.correlationId(),
                "Extraction completed: pages=" + extractionOutput.pageCount()
                        + ", chars=" + extractionOutput.totalCharacterCount()
                        + ", hasText=" + extractionOutput.hasEmbeddedText()
        );

        releaseLockQuietly(command);

        return ExtractDocumentTextResult.of(
                command.documentId(), textArtifact.id(),
                extractionOutput.pageCount(), extractionOutput.totalCharacterCount(),
                extractionOutput.hasEmbeddedText());
    }

    private ExtractDocumentTextResult handleNoEmbeddedText(ExtractDocumentTextCommand command,
                                                            Document document,
                                                            TextExtractionOutput extractionOutput) {
        document.fail();
        documentRepository.save(document);

        TextExtractionFailedEvent failEvent = TextExtractionFailedEvent.of(
                command.documentId(), command.packageId(), command.tenantId(),
                NO_EMBEDDED_TEXT_REASON, command.correlationId());
        eventPublisher.publish(failEvent);

        auditService.recordFailure(
                command.tenantId(),
                SYSTEM_ACTOR,
                AuditAction.DOCUMENT_PROCESSING_FAILED,
                "Document",
                command.documentId().toString(),
                command.correlationId(),
                "Text extraction failed: " + NO_EMBEDDED_TEXT_REASON
        );

        releaseLockQuietly(command);

        return ExtractDocumentTextResult.of(
                command.documentId(), null,
                extractionOutput.pageCount(), 0, false);
    }

    private void handleProcessingFailure(ExtractDocumentTextCommand command,
                                          Document document,
                                          RuntimeException failure) {
        String sanitizedReason = FailureReasonSanitizer.sanitize(failure.getMessage());

        try {
            document.fail();
            documentRepository.save(document);
        } catch (RuntimeException ignored) {
            // Best-effort — do not mask the original processing failure.
        }

        try {
            TextExtractionFailedEvent event = TextExtractionFailedEvent.of(
                    command.documentId(), command.packageId(), command.tenantId(),
                    sanitizedReason, command.correlationId());
            eventPublisher.publish(event);
        } catch (RuntimeException ignored) {
            // Best-effort — do not mask the original processing failure.
        }

        try {
            auditService.recordFailure(
                    command.tenantId(),
                    SYSTEM_ACTOR,
                    AuditAction.DOCUMENT_PROCESSING_FAILED,
                    "Document",
                    command.documentId().toString(),
                    command.correlationId(),
                    "Text extraction failed: " + sanitizedReason
            );
        } catch (RuntimeException ignored) {
            // Best-effort — do not mask the original processing failure.
        }

        releaseLockQuietly(command);
    }

    private void releaseLockQuietly(ExtractDocumentTextCommand command) {
        try {
            processingLockPort.release(command.lockKey(), command.lockToken());
        } catch (RuntimeException ignored) {
            // Best-effort cleanup only.
        }
    }

    private String buildStoragePath(String tenantId, String packageId, String documentId,
                                     String artifactType, String artifactId) {
        return tenantId + "/" + packageId + "/" + documentId + "/" + artifactType + "/" + artifactId;
    }

    private record ExtractionContext(Document document, Artifact originalArtifact) {
    }

    private static final class ExtractionProcessingException extends RuntimeException {
        private ExtractionProcessingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
