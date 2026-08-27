package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.ReviewTaskId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.review.ReviewStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Event raised when a review task reaches a terminal state (approved/rejected).
 */
public record ReviewCompletedEvent(
        ReviewTaskId reviewTaskId,
        PackageId packageId,
        TenantId tenantId,
        ActorId reviewerId,
        ReviewStatus outcome,
        CorrelationId correlationId,
        Instant occurredAt
) implements DomainEvent {

    public ReviewCompletedEvent {
        Objects.requireNonNull(reviewTaskId, "ReviewTaskId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(reviewerId, "ReviewerId must not be null");
        Objects.requireNonNull(outcome, "Outcome must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt must not be null");
    }

    public static ReviewCompletedEvent of(ReviewTaskId reviewTaskId, PackageId packageId,
                                           TenantId tenantId, ActorId reviewerId,
                                           ReviewStatus outcome, CorrelationId correlationId) {
        return new ReviewCompletedEvent(reviewTaskId, packageId, tenantId, reviewerId,
                outcome, correlationId, Instant.now());
    }

    @Override
    public String eventType() {
        return "ReviewCompleted";
    }
}
