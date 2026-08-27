package com.securedoc.platform.application.review.command;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.ReviewTaskId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

public record AssignReviewTaskCommand(
        ReviewTaskId reviewTaskId,
        TenantId tenantId,
        ActorId reviewerId,
        CorrelationId correlationId
) {
    public AssignReviewTaskCommand {
        Objects.requireNonNull(reviewTaskId, "ReviewTaskId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(reviewerId, "ReviewerId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
    }

    public static AssignReviewTaskCommand of(ReviewTaskId reviewTaskId, TenantId tenantId,
                                              ActorId reviewerId, CorrelationId correlationId) {
        return new AssignReviewTaskCommand(reviewTaskId, tenantId, reviewerId, correlationId);
    }
}
