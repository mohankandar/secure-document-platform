package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * Event raised when a package transitions to PROCESSING state.
 * Contains IDs only — no document content or PII.
 */
public record PackageProcessingStartedEvent(
        PackageId packageId,
        TenantId tenantId,
        int documentCount,
        CorrelationId correlationId,
        Instant occurredAt
) implements DomainEvent {

    public PackageProcessingStartedEvent {
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt must not be null");
    }

    public static PackageProcessingStartedEvent of(PackageId packageId, TenantId tenantId,
                                                    int documentCount, CorrelationId correlationId) {
        return new PackageProcessingStartedEvent(packageId, tenantId, documentCount, correlationId, Instant.now());
    }

    @Override
    public String eventType() {
        return "PackageProcessingStarted";
    }
}
