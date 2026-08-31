package com.securedoc.platform.application.intake.query;

import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import java.time.Instant;
import java.util.*;

public record PackageDetail(
        PackageId packageId,
        PackageType packageType,
        PackageStatus status,
        List<BusinessIdentifierSummary> businessIdentifiers,
        Map<String,String> metadata,
        List<DocumentSummary> documents,
        Instant createdAt,
        Instant updatedAt
) {
    public PackageDetail {
        Objects.requireNonNull(packageId); Objects.requireNonNull(packageType); Objects.requireNonNull(status);
        businessIdentifiers = businessIdentifiers == null ? List.of() : List.copyOf(businessIdentifiers);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        documents = documents == null ? List.of() : List.copyOf(documents);
        Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
    }
    public record DocumentSummary(DocumentId documentId, String fileName, String mimeType, long sizeBytes,
                                  DocumentType documentType, DocumentStatus status) {}
}
