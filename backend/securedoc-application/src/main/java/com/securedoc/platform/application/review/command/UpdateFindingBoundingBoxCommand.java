package com.securedoc.platform.application.review.command;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.ReviewTaskId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Command to update a finding's bounding box during review.
 * Tied to an active review task for authorization.
 * Coordinates are normalized (0.0-1.0).
 */
public record UpdateFindingBoundingBoxCommand(
        FindingId findingId,
        ReviewTaskId reviewTaskId,
        TenantId tenantId,
        ActorId actorId,
        double left,
        double top,
        double width,
        double height,
        CorrelationId correlationId
) {
    public UpdateFindingBoundingBoxCommand {
        Objects.requireNonNull(findingId, "FindingId must not be null");
        Objects.requireNonNull(reviewTaskId, "ReviewTaskId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(actorId, "ActorId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        if (left < 0.0 || left > 1.0) throw new IllegalArgumentException("Left must be 0.0-1.0");
        if (top < 0.0 || top > 1.0) throw new IllegalArgumentException("Top must be 0.0-1.0");
        if (width <= 0.0 || width > 1.0) throw new IllegalArgumentException("Width must be > 0.0 and <= 1.0");
        if (height <= 0.0 || height > 1.0) throw new IllegalArgumentException("Height must be > 0.0 and <= 1.0");
        if (left + width > 1.0) throw new IllegalArgumentException("left + width must be <= 1.0");
        if (top + height > 1.0) throw new IllegalArgumentException("top + height must be <= 1.0");
    }

    public static UpdateFindingBoundingBoxCommand of(FindingId findingId, ReviewTaskId reviewTaskId,
                                                      TenantId tenantId, ActorId actorId,
                                                      double left, double top, double width, double height,
                                                      CorrelationId correlationId) {
        return new UpdateFindingBoundingBoxCommand(findingId, reviewTaskId, tenantId, actorId,
                left, top, width, height, correlationId);
    }
}
