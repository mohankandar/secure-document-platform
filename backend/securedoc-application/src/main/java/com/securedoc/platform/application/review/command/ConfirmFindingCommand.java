package com.securedoc.platform.application.review.command;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.ReviewTaskId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Command for confirming a finding as a true positive during review.
 * Tied to an active review task for authorization.
 */
public record ConfirmFindingCommand(
        FindingId findingId,
        ReviewTaskId reviewTaskId,
        TenantId tenantId,
        ActorId actorId,
        String reason,
        CorrelationId correlationId
) {
    public ConfirmFindingCommand {
        Objects.requireNonNull(findingId, "FindingId must not be null");
        Objects.requireNonNull(reviewTaskId, "ReviewTaskId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(actorId, "ActorId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
    }

    public static ConfirmFindingCommand of(FindingId findingId, ReviewTaskId reviewTaskId,
                                            TenantId tenantId, ActorId actorId,
                                            String reason, CorrelationId correlationId) {
        return new ConfirmFindingCommand(findingId, reviewTaskId, tenantId, actorId, reason, correlationId);
    }
}
