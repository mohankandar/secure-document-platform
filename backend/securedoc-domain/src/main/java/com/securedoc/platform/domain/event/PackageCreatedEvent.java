package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * Event raised when a new document package is created.
 */
public record PackageCreatedEvent(
        PackageId packageId,
        TenantId tenantId,
        PackageType packageType,
        ActorId submittedBy,
        CorrelationId correlationId,
        Instant occurredAt
) implements DomainEvent {

    public PackageCreatedEvent {
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(packageType, "PackageType must not be null");
        Objects.requireNonNull(submittedBy, "SubmittedBy must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt must not be null");
    }

    public static PackageCreatedEvent of(PackageId packageId, TenantId tenantId,
                                          PackageType packageType, ActorId submittedBy,
                                          CorrelationId correlationId) {
        return new PackageCreatedEvent(packageId, tenantId, packageType, submittedBy,
                correlationId, Instant.now());
    }

    @Override
    public String eventType() {
        return "PackageCreated";
    }
}
