package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * Event raised when redaction completes successfully for a document.
 * Contains IDs, counts, and safe metadata only — never raw sensitive values.
 */
public record RedactionCompletedEvent(
        DocumentId documentId,
        PackageId packageId,
        TenantId tenantId,
        ArtifactId redactedArtifactId,
        int redactionCount,
        CorrelationId correlationId,
        Instant occurredAt
) implements DomainEvent {

    public RedactionCompletedEvent {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(redactedArtifactId, "RedactedArtifactId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt must not be null");
    }

    public static RedactionCompletedEvent of(DocumentId documentId, PackageId packageId,
                                              TenantId tenantId, ArtifactId redactedArtifactId,
                                              int redactionCount, CorrelationId correlationId) {
        return new RedactionCompletedEvent(documentId, packageId, tenantId,
                redactedArtifactId, redactionCount, correlationId, Instant.now());
    }

    @Override
    public String eventType() {
        return "RedactionCompleted";
    }
}
