package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * Event raised when a document is uploaded to a package.
 * Does not include raw file name, OCR text, or sensitive content.
 */
public record DocumentUploadedEvent(
        DocumentId documentId,
        PackageId packageId,
        TenantId tenantId,
        ArtifactId artifactId,
        CorrelationId correlationId,
        Instant occurredAt
) implements DomainEvent {

    public DocumentUploadedEvent {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(artifactId, "ArtifactId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt must not be null");
    }

    public static DocumentUploadedEvent of(DocumentId documentId, PackageId packageId,
                                            TenantId tenantId, ArtifactId artifactId,
                                            CorrelationId correlationId) {
        return new DocumentUploadedEvent(documentId, packageId, tenantId, artifactId,
                correlationId, Instant.now());
    }

    @Override
    public String eventType() {
        return "DocumentUploaded";
    }
}
