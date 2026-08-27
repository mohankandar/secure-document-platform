package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * Event raised when a document transitions to EXTRACTING state.
 * Contains IDs only — no document content, file names, or PII.
 */
public record DocumentProcessingStartedEvent(
        DocumentId documentId,
        PackageId packageId,
        TenantId tenantId,
        CorrelationId correlationId,
        Instant occurredAt
) implements DomainEvent {

    public DocumentProcessingStartedEvent {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt must not be null");
    }

    public static DocumentProcessingStartedEvent of(DocumentId documentId, PackageId packageId,
                                                     TenantId tenantId, CorrelationId correlationId) {
        return new DocumentProcessingStartedEvent(documentId, packageId, tenantId, correlationId, Instant.now());
    }

    @Override
    public String eventType() {
        return "DocumentProcessingStarted";
    }
}
