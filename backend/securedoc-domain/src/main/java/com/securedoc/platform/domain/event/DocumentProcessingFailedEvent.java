package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * Event raised when a document's processing fails.
 * Contains IDs only — no document content, file names, or PII.
 * The failure reason is a safe, sanitized message (no sensitive data).
 */
public record DocumentProcessingFailedEvent(
        DocumentId documentId,
        PackageId packageId,
        TenantId tenantId,
        String failureReason,
        CorrelationId correlationId,
        Instant occurredAt
) implements DomainEvent {

    public DocumentProcessingFailedEvent {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt must not be null");
        if (failureReason == null || failureReason.isBlank()) {
            failureReason = "Unknown failure";
        }
    }

    public static DocumentProcessingFailedEvent of(DocumentId documentId, PackageId packageId,
                                                    TenantId tenantId, String failureReason,
                                                    CorrelationId correlationId) {
        return new DocumentProcessingFailedEvent(documentId, packageId, tenantId, failureReason,
                correlationId, Instant.now());
    }

    @Override
    public String eventType() {
        return "DocumentProcessingFailed";
    }
}
