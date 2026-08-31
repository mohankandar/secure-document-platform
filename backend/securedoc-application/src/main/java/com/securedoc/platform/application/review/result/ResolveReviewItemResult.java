package com.securedoc.platform.application.review.result;

import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.PackageId;

public record ResolveReviewItemResult(
        FindingId findingId,
        PackageId packageId,
        FindingStatus status,
        boolean processingResumed
) {}
