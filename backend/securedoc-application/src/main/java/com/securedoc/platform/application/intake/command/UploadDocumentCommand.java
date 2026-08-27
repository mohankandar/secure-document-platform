package com.securedoc.platform.application.intake.command;

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
        CorrelationId correlationId
) {

    public UploadDocumentCommand {
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(fileName, "FileName must not be null");
        Objects.requireNonNull(mimeType, "MimeType must not be null");
        Objects.requireNonNull(content, "Content must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        if (fileName.isBlank()) {
            throw new IllegalArgumentException("FileName must not be blank");
        }
        if (mimeType.isBlank()) {
            throw new IllegalArgumentException("MimeType must not be blank");
        }
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

    public long contentLength() {
        return content.contentLength();
    }
}
