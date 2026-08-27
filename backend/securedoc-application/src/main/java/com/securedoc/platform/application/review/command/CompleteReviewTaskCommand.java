package com.securedoc.platform.application.review.command;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.ReviewTaskId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

public record CompleteReviewTaskCommand(
        ReviewTaskId reviewTaskId,
        TenantId tenantId,
        ActorId reviewerId,
        String comment,
        CorrelationId correlationId
) {
    public CompleteReviewTaskCommand {
        Objects.requireNonNull(reviewTaskId, "ReviewTaskId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(reviewerId, "ReviewerId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        // comment may be null
    }

    public static CompleteReviewTaskCommand of(ReviewTaskId reviewTaskId, TenantId tenantId,
                                                ActorId reviewerId, String comment,
                                                CorrelationId correlationId) {
        return new CompleteReviewTaskCommand(reviewTaskId, tenantId, reviewerId, comment, correlationId);
    }
}
