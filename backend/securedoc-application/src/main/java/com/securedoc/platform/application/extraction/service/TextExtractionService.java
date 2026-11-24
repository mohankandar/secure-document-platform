package com.securedoc.platform.application.extraction.service;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.application.exception.ApplicationException;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.extraction.command.ExtractDocumentTextCommand;
import com.securedoc.platform.application.extraction.result.ExtractDocumentTextResult;
import com.securedoc.platform.application.pipeline.PageBatch;
import com.securedoc.platform.application.pipeline.PageBatchCommand;
import com.securedoc.platform.application.pipeline.PageBatchFiles;
import com.securedoc.platform.application.port.in.ExtractDocumentTextUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.ProcessingLockPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.application.port.out.TextExtractionPort.ExtractionSource;
import com.securedoc.platform.application.port.out.TextExtractionPort.PageText;
import com.securedoc.platform.application.port.out.TextExtractionPort.TextExtractionOutput;
import com.securedoc.platform.application.processing.service.FailureReasonSanitizer;
import com.securedoc.platform.application.resilience.TechnicalFailureClassifier;
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
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(TextExtractionService.class);
    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    private static final String TEXT_MIME_TYPE = "text/plain";
    /** Joins page texts in the EXTRACTED_TEXT artifact. Finding offsets are relative to each page. */
    public static final String PAGE_SEPARATOR = "\n\n--- Page Break ---\n\n";
    private static final String NO_READABLE_TEXT_REASON = "No readable text could be normalized from a non-blank document";

    private final DocumentRepository documentRepository;
    private final PackageRepository packageRepository;
    private final ArtifactRepository artifactRepository;
    private final StoragePort storagePort;
    private final TextExtractionPort textExtractionPort;
    private final ChecksumPort checksumPort;
    private final ProcessingLockPort processingLockPort;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;
    private final PageBatchFiles batchFiles;

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
        this.batchFiles = new PageBatchFiles(artifactRepository, storagePort, checksumPort);
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

    /**
     * Task 0049: the same page reading as {@link #execute}, for one batch's pages. The document keeps its status; its
     * state, text artifact, event and audit are written once, when the batches are gathered.
     */
    @Override
    public void extractBatch(PageBatchCommand command) {
        Objects.requireNonNull(command, "Command must not be null");
        Document document = documentRepository
                .findByIdAndTenantId(command.documentId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Document", command.documentId().toString()));
        if (!document.packageId().equals(command.packageId())) {
            throw new ValidationException(LvErrorCode.PRC_009, "Document does not belong to the specified package");
        }
        if (document.status() != DocumentStatus.UPLOADED) {
            throw new ValidationException(LvErrorCode.PRC_009, "Document is not waiting for its text to be read");
        }
        try {
            long startedNanos = System.nanoTime();
            TextExtractionOutput output;
            try (InputStream content = storagePort.retrieve(originalArtifact(document).storageReference())) {
                output = textExtractionPort.extractPages(content, document.mimeType().value(),
                        command.batch().pages());
            } catch (ApplicationException e) {
                throw e;
            } catch (Exception e) {
                throw new ExtractionProcessingException("Text extraction failed", e);
            }
            batchFiles.save(ArtifactType.EXTRACTED_TEXT_BATCH, command, out -> {
                out.writeBoolean(output.hasEmbeddedText());
                out.writeBoolean(output.usedOcr());
                out.writeInt(output.pages().size());
                for (PageText page : output.pages()) {
                    out.writeInt(page.pageNumber());
                    out.writeInt(page.width());
                    out.writeInt(page.height());
                    PageBatchFiles.writeText(out, page.source().name());
                    out.writeDouble(page.sourceConfidence());
                    PageBatchFiles.writeText(out, page.text());
                }
            });
            log.info("DOCUMENT_TEXT_BATCH_READ document={} package={} pages={}-{} ocrPages={} durationMs={}",
                    command.documentId(), command.packageId(), command.batch().from(), command.batch().to(),
                    output.ocrPageCount(), elapsedMillis(startedNanos));
        } catch (RuntimeException e) {
            recordProcessingFailure(command.documentId(), command.packageId(), command.tenantId(),
                    command.correlationId(), document, e);
            throw e;
        }
    }

    @Override
    public ExtractDocumentTextResult completeFromBatches(ExtractDocumentTextCommand command, List<PageBatch> batches) {
        Objects.requireNonNull(command, "Command must not be null");
        ExtractionContext context;
        try {
            context = loadAndValidate(command);
        } catch (RuntimeException e) {
            releaseLockQuietly(command);
            throw e;
        }
        try {
            long startedNanos = System.nanoTime();
            TextExtractionOutput gathered = gather(batchFiles.load(ArtifactType.EXTRACTED_TEXT_BATCH,
                    command.documentId(), command.tenantId(), batches, TextExtractionService::readBatch));
            if (batches.isEmpty() || gathered.pages().size()
                    != batches.stream().mapToInt(PageBatch::to).max().getAsInt()) {
                throw new IllegalStateException("The page batches do not cover every page once");
            }
            ExtractDocumentTextResult result = complete(command, context.document(), gathered, startedNanos);
            batchFiles.discard(ArtifactType.EXTRACTED_TEXT_BATCH, command.documentId(), command.tenantId());
            return result;
        } catch (RuntimeException e) {
            handleProcessingFailure(command, context.document(), e);
            throw e;
        }
    }

    private static TextExtractionOutput readBatch(DataInputStream in) throws IOException {
        boolean embedded = in.readBoolean();
        boolean ocr = in.readBoolean();
        int count = in.readInt();
        List<PageText> pages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int number = in.readInt();
            int width = in.readInt();
            int height = in.readInt();
            ExtractionSource source = ExtractionSource.valueOf(PageBatchFiles.readText(in));
            double confidence = in.readDouble();
            pages.add(new PageText(number, width, height, PageBatchFiles.readText(in), List.of(), source, confidence));
        }
        return new TextExtractionOutput(pages, pages.stream().mapToLong(p -> p.text().length()).sum(), embedded, ocr);
    }

    /** Every page exactly once, in order; a gap or a double page refuses the document (fail closed). */
    private static TextExtractionOutput gather(List<TextExtractionOutput> batches) {
        List<PageText> pages = new ArrayList<>();
        for (TextExtractionOutput batch : batches) pages.addAll(batch.pages());
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).pageNumber() != i + 1) {
                throw new IllegalStateException("The page batches do not cover every page once");
            }
        }
        return new TextExtractionOutput(pages,
                batches.stream().mapToLong(TextExtractionOutput::totalCharacterCount).sum(),
                batches.stream().anyMatch(TextExtractionOutput::hasEmbeddedText),
                batches.stream().anyMatch(TextExtractionOutput::usedOcr));
    }

    private ExtractionContext loadAndValidate(ExtractDocumentTextCommand command) {
        Document document = documentRepository
                .findByIdAndTenantId(command.documentId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Document", command.documentId().toString()));

        if (!document.packageId().equals(command.packageId())) {
            throw new ValidationException(LvErrorCode.PRC_009, "Document does not belong to the specified package");
        }

        DocumentPackage parentPackage = packageRepository
                .findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));

        if (parentPackage.status() != PackageStatus.PROCESSING) {
            throw new ValidationException(LvErrorCode.PRC_009, "Package is not in PROCESSING status");
        }

        if (document.status() != DocumentStatus.EXTRACTING) {
            throw new ValidationException(LvErrorCode.PRC_009, "Document is not in EXTRACTING status");
        }

        return new ExtractionContext(document, originalArtifact(document));
    }

    private Artifact originalArtifact(Document document) {
        return artifactRepository
                .findByDocumentIdAndTenantId(document.id(), document.tenantId())
                .stream()
                .filter(a -> a.artifactType() == ArtifactType.ORIGINAL && a.isAvailable())
                .findFirst()
                // Task 0026 (#355): the original is missing from storage, LV-PRC-007.
                .orElseThrow(() -> new NotFoundException("Original artifact for document",
                        document.id().toString(), com.securedoc.platform.domain.error.LvErrorCode.PRC_007));
    }

    private ExtractDocumentTextResult extractAndComplete(ExtractDocumentTextCommand command,
                                                          ExtractionContext context) {
        long startedNanos = System.nanoTime();
        Document document = context.document();

        TextExtractionOutput extractionOutput;
        try (InputStream pdfContent = storagePort.retrieve(context.originalArtifact().storageReference())) {
            extractionOutput = textExtractionPort.extract(pdfContent, document.mimeType().value());
        } catch (ApplicationException e) {
            throw e;
        } catch (Exception e) {
            throw new ExtractionProcessingException("Text extraction failed", e);
        }
        return complete(command, document, extractionOutput, startedNanos);
    }

    /** Writes the document's pages, text artifact, state, event and audit (whole document or gathered batches). */
    private ExtractDocumentTextResult complete(ExtractDocumentTextCommand command, Document document,
                                               TextExtractionOutput extractionOutput, long startedNanos) {
        if (extractionOutput.pages().isEmpty()) {
            return handleNoReadableText(command, document, extractionOutput);
        }

        List<Page> pages = extractionOutput.pages().stream()
                .map(pt -> pt.text().isBlank()
                        ? Page.of(pt.pageNumber(), pt.width(), pt.height())
                        : Page.withText(pt.pageNumber(), pt.width(), pt.height(), pt.text()))
                .toList();

        String fullText = extractionOutput.pages().stream()
                .map(PageText::text)
                .reduce((a, b) -> a + PAGE_SEPARATOR + b)
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

        ArtifactStorageReference storageRef = storagePort.storeForPackage(
                command.tenantId(),
                command.packageId(),
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
                command.documentId().toString(), command.packageId(),
                command.correlationId(),
                "Extraction completed: pages=" + extractionOutput.pageCount()
                        + ", chars=" + extractionOutput.totalCharacterCount()
                        + ", hasText=" + extractionOutput.hasUsableText()
                        + ", embeddedText=" + extractionOutput.hasEmbeddedText()
                        + ", usedOcr=" + extractionOutput.usedOcr()
                        + ", nativePages=" + extractionOutput.nativePageCount()
                        + ", ocrPages=" + extractionOutput.ocrPageCount()
        );

        releaseLockQuietly(command);

        log.info("DOCUMENT_EXTRACTION_COMPLETED document={} package={} pages={} nativePages={} ocrPages={} chars={} durationMs={}",
                command.documentId(), command.packageId(), extractionOutput.pageCount(),
                extractionOutput.nativePageCount(), extractionOutput.ocrPageCount(),
                extractionOutput.totalCharacterCount(), elapsedMillis(startedNanos));

        return ExtractDocumentTextResult.of(
                command.documentId(), textArtifact.id(),
                extractionOutput.pageCount(), extractionOutput.totalCharacterCount(),
                extractionOutput.hasUsableText(), extractionOutput.hasEmbeddedText(), extractionOutput.usedOcr());
    }

    private ExtractDocumentTextResult handleNoReadableText(ExtractDocumentTextCommand command,
                                                            Document document,
                                                            TextExtractionOutput extractionOutput) {
        document.fail();
        documentRepository.save(document);

        TextExtractionFailedEvent failEvent = TextExtractionFailedEvent.of(
                command.documentId(), command.packageId(), command.tenantId(),
                NO_READABLE_TEXT_REASON, command.correlationId());
        eventPublisher.publish(failEvent);

        auditService.recordFailure(
                command.tenantId(),
                SYSTEM_ACTOR,
                AuditAction.DOCUMENT_PROCESSING_FAILED,
                "Document",
                command.documentId().toString(), command.packageId(),
                command.correlationId(),
                "Text extraction failed: " + NO_READABLE_TEXT_REASON
        );

        releaseLockQuietly(command);

        return ExtractDocumentTextResult.of(
                command.documentId(), null,
                extractionOutput.pageCount(), 0, false,
                extractionOutput.hasEmbeddedText(), extractionOutput.usedOcr());
    }

    private void handleProcessingFailure(ExtractDocumentTextCommand command,
                                          Document document,
                                          RuntimeException failure) {
        recordProcessingFailure(command.documentId(), command.packageId(), command.tenantId(),
                command.correlationId(), document, failure);
        releaseLockQuietly(command);
    }

    private void recordProcessingFailure(com.securedoc.platform.domain.identity.DocumentId documentId,
                                         com.securedoc.platform.domain.identity.PackageId packageId,
                                         com.securedoc.platform.domain.identity.TenantId tenantId,
                                         CorrelationId correlationId, Document document, RuntimeException failure) {
        String safeReason = TechnicalFailureClassifier.safeAuditDetail(failure, FailureReasonSanitizer.textExtractionFailed());

        try {
            document.fail();
            documentRepository.save(document);
        } catch (RuntimeException ignored) {
            // Best-effort — do not mask the original processing failure.
        }

        try {
            TextExtractionFailedEvent event = TextExtractionFailedEvent.of(
                    documentId, packageId, tenantId,
                    safeReason, correlationId);
            eventPublisher.publish(event);
        } catch (RuntimeException ignored) {
            // Best-effort — do not mask the original processing failure.
        }

        try {
            auditService.recordFailure(
                    tenantId,
                    SYSTEM_ACTOR,
                    AuditAction.DOCUMENT_PROCESSING_FAILED,
                    "Document",
                    documentId.toString(), packageId,
                    correlationId,
                    safeReason
            );
        } catch (RuntimeException ignored) {
            // Best-effort — do not mask the original processing failure.
        }
    }

    private void releaseLockQuietly(ExtractDocumentTextCommand command) {
        try {
            processingLockPort.release(command.lockKey(), command.lockToken());
        } catch (RuntimeException ignored) {
            // Best-effort cleanup only.
        }
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
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
