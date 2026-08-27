package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * Event raised when sensitive-data detection completes for a document.
 * Contains counts and summary only — never raw sensitive values or extracted text.
 */
public record DocumentDetectionCompletedEvent(
        DocumentId documentId,
        PackageId packageId,
        TenantId tenantId,
        int findingCount,
        boolean requiresRedaction,
        CorrelationId correlationId,
        Instant occurredAt
) implements DomainEvent {

    public DocumentDetectionCompletedEvent {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt must not be null");
    }

    public static DocumentDetectionCompletedEvent of(DocumentId documentId, PackageId packageId,
                                                      TenantId tenantId, int findingCount,
                                                      boolean requiresRedaction,
                                                      CorrelationId correlationId) {
        return new DocumentDetectionCompletedEvent(documentId, packageId, tenantId,
                findingCount, requiresRedaction, correlationId, Instant.now());
    }

    @Override
    public String eventType() {
        return "DocumentDetectionCompleted";
    }
}
