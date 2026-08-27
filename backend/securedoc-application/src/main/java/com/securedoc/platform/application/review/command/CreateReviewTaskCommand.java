package com.securedoc.platform.application.review.command;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Command to create a review task for a package in PENDING_REVIEW status.
 */
public record CreateReviewTaskCommand(
        PackageId packageId,
        TenantId tenantId,
        CorrelationId correlationId
) {
    public CreateReviewTaskCommand {
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
    }

    public static CreateReviewTaskCommand of(PackageId packageId, TenantId tenantId,
                                              CorrelationId correlationId) {
        return new CreateReviewTaskCommand(packageId, tenantId, correlationId);
    }
}
