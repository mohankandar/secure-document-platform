package com.securedoc.platform.domain.lifecycle;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable record of one successful, package-atomic delivery cycle. */
public record PackageExportRecord(
        UUID id,
        TenantId tenantId,
        PackageId packageId,
        int cycleNumber,
        String destinationType,
        Instant exportedAt,
        CorrelationId correlationId,
        List<ExportedDocument> documents
) {
    public PackageExportRecord {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(packageId);
        if (cycleNumber < 1) throw new IllegalArgumentException("cycleNumber must be >= 1");
        if (destinationType == null || destinationType.isBlank()) throw new IllegalArgumentException("destinationType is required");
        Objects.requireNonNull(exportedAt);
        Objects.requireNonNull(correlationId);
        documents = List.copyOf(documents == null ? List.of() : documents);
        if (documents.isEmpty()) throw new IllegalArgumentException("At least one exported document is required");
    }

    public static PackageExportRecord create(TenantId tenantId, PackageId packageId, int cycleNumber,
                                             String destinationType, Instant exportedAt,
                                             CorrelationId correlationId, List<ExportedDocument> documents) {
        return new PackageExportRecord(UUID.randomUUID(), tenantId, packageId, cycleNumber,
                destinationType, exportedAt, correlationId, documents);
    }

    public record ExportedDocument(
            DocumentId documentId,
            String fileName,
            long sizeBytes,
            Checksum checksum,
            String destinationLocation
    ) {
        public ExportedDocument {
            Objects.requireNonNull(documentId);
            if (fileName == null || fileName.isBlank()) throw new IllegalArgumentException("fileName is required");
            if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes must be >= 0");
            Objects.requireNonNull(checksum);
            if (destinationLocation == null || destinationLocation.isBlank()) throw new IllegalArgumentException("destinationLocation is required");
        }
    }
}
