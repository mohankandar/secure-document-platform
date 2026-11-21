package com.securedoc.platform.application.intake.query;

import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import java.time.Instant;
import java.util.*;

/**
 * Read model of one case. {@code sensitivityLevel} is the case level as sent by the source system and
 * {@code effectiveSensitivityLevel} the case's effective level (task 0021); both are read-only.
 */
public record PackageDetail(
        PackageId packageId,
        PackageType packageType,
        PackageStatus status,
        List<BusinessIdentifierSummary> businessIdentifiers,
        Map<String,String> metadata,
        List<DocumentSummary> documents,
        Instant createdAt,
        Instant updatedAt,
        SensitivityLevel sensitivityLevel,
        SensitivityLevel effectiveSensitivityLevel
) {
    public PackageDetail {
        Objects.requireNonNull(packageId); Objects.requireNonNull(packageType); Objects.requireNonNull(status);
        businessIdentifiers = businessIdentifiers == null ? List.of() : List.copyOf(businessIdentifiers);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        documents = documents == null ? List.of() : List.copyOf(documents);
        Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        sensitivityLevel = sensitivityLevel == null ? SensitivityLevel.STANDARD : sensitivityLevel;
        effectiveSensitivityLevel = effectiveSensitivityLevel == null
                ? SensitivityLevel.effectiveForCase(sensitivityLevel,
                        documents.stream().map(DocumentSummary::sensitivityLevel).toList())
                : effectiveSensitivityLevel;
    }

    public PackageDetail(PackageId packageId, PackageType packageType, PackageStatus status,
                         List<BusinessIdentifierSummary> businessIdentifiers, Map<String,String> metadata,
                         List<DocumentSummary> documents, Instant createdAt, Instant updatedAt) {
        this(packageId, packageType, status, businessIdentifiers, metadata, documents, createdAt, updatedAt,
                SensitivityLevel.STANDARD, null);
    }

    public record DocumentSummary(DocumentId documentId, String fileName, String mimeType, long sizeBytes,
                                  DocumentType documentType, DocumentStatus status,
                                  SensitivityLevel sensitivityLevel) {
        public DocumentSummary {
            sensitivityLevel = sensitivityLevel == null ? SensitivityLevel.STANDARD : sensitivityLevel;
        }

        public DocumentSummary(DocumentId documentId, String fileName, String mimeType, long sizeBytes,
                               DocumentType documentType, DocumentStatus status) {
            this(documentId, fileName, mimeType, sizeBytes, documentType, status, SensitivityLevel.STANDARD);
        }
    }
}
