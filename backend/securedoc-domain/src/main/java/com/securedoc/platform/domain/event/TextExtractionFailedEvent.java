package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * Event raised when text extraction fails for a document.
 * Contains IDs and a sanitized failure reason only — no document content or PII.
 */
public record TextExtractionFailedEvent(
        DocumentId documentId,
        PackageId packageId,
        TenantId tenantId,
        String failureReason,
        CorrelationId correlationId,
        Instant occurredAt
) implements DomainEvent {

    public TextExtractionFailedEvent {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt must not be null");
        if (failureReason == null || failureReason.isBlank()) {
            failureReason = "Unknown extraction failure";
        }
    }

    public static TextExtractionFailedEvent of(DocumentId documentId, PackageId packageId,
                                                TenantId tenantId, String failureReason,
                                                CorrelationId correlationId) {
        return new TextExtractionFailedEvent(documentId, packageId, tenantId,
                failureReason, correlationId, Instant.now());
    }

    @Override
    public String eventType() {
        return "TextExtractionFailed";
    }
}
