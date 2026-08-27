package com.securedoc.platform.application.review.command;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.ReviewTaskId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Command for finding-level review actions (confirm, dismiss).
 * Tied to an active review task for authorization.
 */
public record FindingReviewCommand(
        FindingId findingId,
        ReviewTaskId reviewTaskId,
        TenantId tenantId,
        ActorId actorId,
        String reason,
        CorrelationId correlationId
) {
    public FindingReviewCommand {
        Objects.requireNonNull(findingId, "FindingId must not be null");
        Objects.requireNonNull(reviewTaskId, "ReviewTaskId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(actorId, "ActorId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
    }

    public static FindingReviewCommand of(FindingId findingId, ReviewTaskId reviewTaskId,
                                           TenantId tenantId, ActorId actorId,
                                           String reason, CorrelationId correlationId) {
        return new FindingReviewCommand(findingId, reviewTaskId, tenantId, actorId, reason, correlationId);
    }
}
