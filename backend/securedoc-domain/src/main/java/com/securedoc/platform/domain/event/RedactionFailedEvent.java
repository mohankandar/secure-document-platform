package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * Event raised when redaction fails for a document.
 * Contains IDs and a sanitized failure reason only — no sensitive values or text.
 */
public record RedactionFailedEvent(
        DocumentId documentId,
        PackageId packageId,
        TenantId tenantId,
        String failureReason,
        CorrelationId correlationId,
        Instant occurredAt
) implements DomainEvent {

    public RedactionFailedEvent {
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt must not be null");
        if (failureReason == null || failureReason.isBlank()) {
            failureReason = "Unknown redaction failure";
        }
    }

    public static RedactionFailedEvent of(DocumentId documentId, PackageId packageId,
                                            TenantId tenantId, String failureReason,
                                            CorrelationId correlationId) {
        return new RedactionFailedEvent(documentId, packageId, tenantId,
                failureReason, correlationId, Instant.now());
    }

    @Override
    public String eventType() {
        return "RedactionFailed";
    }
}
