package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.review.result.ReviewPagePreviewResult;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.TenantId;

/**
 * Loads only the relevant page image required for a pending focused-review item.
 * This is intentionally not a general original-document download use case.
 */
public interface GetReviewPagePreviewQuery {
    ReviewPagePreviewResult execute(
            TenantId tenantId,
            FindingId findingId,
            ActorId actorId,
            CorrelationId correlationId);
}
