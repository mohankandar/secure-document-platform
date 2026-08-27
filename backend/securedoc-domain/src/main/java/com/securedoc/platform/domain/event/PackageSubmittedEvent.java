package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * Event raised when a document package is submitted for processing.
 */
public record PackageSubmittedEvent(
        PackageId packageId,
        TenantId tenantId,
        int documentCount,
        CorrelationId correlationId,
        Instant occurredAt
) implements DomainEvent {

    public PackageSubmittedEvent {
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt must not be null");
    }

    public static PackageSubmittedEvent of(PackageId packageId, TenantId tenantId,
                                            int documentCount, CorrelationId correlationId) {
        return new PackageSubmittedEvent(packageId, tenantId, documentCount, correlationId, Instant.now());
    }

    @Override
    public String eventType() {
        return "PackageSubmitted";
    }
}
