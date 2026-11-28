package com.securedoc.platform.application.intake.command;

import com.securedoc.platform.domain.document.SensitivityLevel;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;

import java.util.Objects;

/**
 * Command to upload a document to an existing package.
 * Uses UploadContent for framework-neutral, replayable content access.
 */
public record UploadDocumentCommand(
        PackageId packageId,
        String fileName,
        String mimeType,
        UploadContent content,
        CorrelationId correlationId,
        SensitivityLevel sensitivityLevel,
        /** Task 0104: set for a file of a delivered case's next batch, with the id its batch classification gave it; null otherwise. */
        com.securedoc.platform.domain.identity.DocumentId nextBatchDocumentId
) {

    public UploadDocumentCommand {
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(fileName, "FileName must not be null");
        Objects.requireNonNull(mimeType, "MimeType must not be null");
        Objects.requireNonNull(content, "Content must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        sensitivityLevel = sensitivityLevel == null ? SensitivityLevel.STANDARD : sensitivityLevel;
        if (fileName.isBlank()) {
            throw new IllegalArgumentException("FileName must not be blank");
        }
        if (mimeType.isBlank()) {
            throw new IllegalArgumentException("MimeType must not be blank");
        }
    }

    /** An upload with no document level sent (Standard). */
    public UploadDocumentCommand(PackageId packageId, String fileName, String mimeType, UploadContent content,
                                 CorrelationId correlationId) {
        this(packageId, fileName, mimeType, content, correlationId, SensitivityLevel.STANDARD, null);
    }

    /** An upload with the document level the source system sent (task 0021). */
    public static UploadDocumentCommand of(PackageId packageId, String fileName,
                                            String mimeType, UploadContent content,
                                            CorrelationId correlationId, SensitivityLevel sensitivityLevel) {
        return new UploadDocumentCommand(packageId, fileName, mimeType, content, correlationId, sensitivityLevel, null);
    }

    public static UploadDocumentCommand of(PackageId packageId, String fileName,
                                            String mimeType, UploadContent content,
                                            CorrelationId correlationId) {
        return new UploadDocumentCommand(packageId, fileName, mimeType, content, correlationId);
    }

    /**
     * Convenience factory for non-API callers that do not already have a correlation id.
     */
    public static UploadDocumentCommand of(PackageId packageId, String fileName,
                                            String mimeType, UploadContent content) {
        return new UploadDocumentCommand(packageId, fileName, mimeType, content, CorrelationId.generate());
    }

    /**
     * Task 0104: a file of the next batch of a delivered case (a new document or a new version), stored under the id its
     * batch classification gave it, so later files of the same batch can name it as the version they follow.
     */
    public static UploadDocumentCommand forNextBatch(PackageId packageId, String fileName, String mimeType,
                                                     UploadContent content, CorrelationId correlationId,
                                                     SensitivityLevel sensitivityLevel,
                                                     com.securedoc.platform.domain.identity.DocumentId documentId) {
        return new UploadDocumentCommand(packageId, fileName, mimeType, content, correlationId, sensitivityLevel,
                Objects.requireNonNull(documentId, "documentId"));
    }

    public boolean isForNextBatch() {
        return nextBatchDocumentId != null;
    }

    public long contentLength() {
        return content.contentLength();
    }
}
