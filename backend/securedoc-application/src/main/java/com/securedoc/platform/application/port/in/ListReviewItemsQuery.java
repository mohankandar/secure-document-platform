package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.review.result.ReviewItemSummary;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.List;

public interface ListReviewItemsQuery {
    List<ReviewItemSummary> execute(TenantId tenantId, int limit);
}
