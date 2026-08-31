package com.securedoc.platform.application.review.command;

import com.securedoc.platform.application.review.model.ReviewResolution;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/** Focused V1 review action. Bounding-box values are supplied only by visual adjustment tooling. */
public record ResolveReviewItemCommand(
        FindingId findingId,
        TenantId tenantId,
        ActorId actorId,
        ReviewResolution resolution,
        String reason,
        Double left,
        Double top,
        Double width,
        Double height,
        CorrelationId correlationId
) {
    public ResolveReviewItemCommand {
        Objects.requireNonNull(findingId);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(actorId);
        Objects.requireNonNull(resolution);
        Objects.requireNonNull(correlationId);
        boolean anyBox = left != null || top != null || width != null || height != null;
        boolean allBox = left != null && top != null && width != null && height != null;
        if (anyBox && !allBox) {
            throw new IllegalArgumentException("Bounding box must provide left, top, width and height together");
        }
    }

    public boolean hasBoundingBoxAdjustment() {
        return left != null;
    }
}
