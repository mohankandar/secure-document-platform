package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.ReviewTaskId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * Event raised when a new review task is created for a package.
 * Contains IDs and safe metadata only.
 */
public record ReviewTaskCreatedEvent(
        ReviewTaskId reviewTaskId,
        PackageId packageId,
        TenantId tenantId,
        CorrelationId correlationId,
        Instant occurredAt
) implements DomainEvent {

    public ReviewTaskCreatedEvent {
        Objects.requireNonNull(reviewTaskId, "ReviewTaskId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt must not be null");
    }

    public static ReviewTaskCreatedEvent of(ReviewTaskId reviewTaskId, PackageId packageId,
                                             TenantId tenantId, CorrelationId correlationId) {
        return new ReviewTaskCreatedEvent(reviewTaskId, packageId, tenantId, correlationId, Instant.now());
    }

    @Override
    public String eventType() {
        return "ReviewTaskCreated";
    }
}
