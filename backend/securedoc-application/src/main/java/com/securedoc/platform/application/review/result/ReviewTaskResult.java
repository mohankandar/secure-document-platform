package com.securedoc.platform.application.review.result;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.ReviewTaskId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.review.ReviewStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Result/summary of a review task operation.
 * Safe metadata only — no sensitive values.
 */
public record ReviewTaskResult(
        ReviewTaskId reviewTaskId,
        PackageId packageId,
        TenantId tenantId,
        ReviewStatus status,
        ActorId assignedTo,
        Instant createdAt,
        Instant updatedAt
) {
    public ReviewTaskResult {
        Objects.requireNonNull(reviewTaskId, "ReviewTaskId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(status, "Status must not be null");
    }

    public static ReviewTaskResult from(com.securedoc.platform.domain.review.ReviewTask task) {
        return new ReviewTaskResult(
                task.id(), task.packageId(), task.tenantId(),
                task.status(), task.assignedTo().orElse(null),
                task.createdAt(), task.updatedAt()
        );
    }
}
