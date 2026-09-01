package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.review.result.ReviewQueueResult;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Optional;

public interface ListReviewItemsQuery {
    ReviewQueueResult execute(TenantId tenantId, Optional<PackageId> packageId, int limit);

    default ReviewQueueResult execute(TenantId tenantId, int limit) {
        return execute(tenantId, Optional.empty(), limit);
    }
}
