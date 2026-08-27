package com.securedoc.platform.application.intake.query;

import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Full detail projection of a document package including document summaries.
 */
public record PackageDetail(
        PackageId packageId,
        PackageType packageType,
        PackageStatus status,
        Map<String, String> metadata,
        List<DocumentSummary> documents,
        Instant createdAt,
        Instant updatedAt
) {

    public PackageDetail {
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(packageType, "PackageType must not be null");
        Objects.requireNonNull(status, "Status must not be null");
        Objects.requireNonNull(metadata, "Metadata must not be null");
        Objects.requireNonNull(documents, "Documents must not be null");
        Objects.requireNonNull(createdAt, "CreatedAt must not be null");
        Objects.requireNonNull(updatedAt, "UpdatedAt must not be null");
        metadata = Map.copyOf(metadata);
        documents = List.copyOf(documents);
    }

    /**
     * Summary of a document within a package detail view.
     */
    public record DocumentSummary(
            DocumentId documentId,
            String fileName,
            String mimeType,
            long sizeBytes,
            DocumentType documentType,
            DocumentStatus status
    ) {

        public DocumentSummary {
            Objects.requireNonNull(documentId, "DocumentId must not be null");
            Objects.requireNonNull(fileName, "FileName must not be null");
            Objects.requireNonNull(mimeType, "MimeType must not be null");
            Objects.requireNonNull(documentType, "DocumentType must not be null");
            Objects.requireNonNull(status, "Status must not be null");
        }
    }
}
