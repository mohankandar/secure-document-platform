package com.securedoc.platform.application.intake.query;

import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.PackageId;

import java.time.Instant;
import java.util.Objects;

/**
 * Lightweight summary projection of a document package.
 */
public record PackageSummary(
        PackageId packageId,
        PackageType packageType,
        PackageStatus status,
        int documentCount,
        Instant createdAt,
        Instant updatedAt
) {

    public PackageSummary {
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(packageType, "PackageType must not be null");
        Objects.requireNonNull(status, "Status must not be null");
        Objects.requireNonNull(createdAt, "CreatedAt must not be null");
        Objects.requireNonNull(updatedAt, "UpdatedAt must not be null");
    }

    public static PackageSummary of(PackageId packageId, PackageType packageType,
                                     PackageStatus status, int documentCount,
                                     Instant createdAt, Instant updatedAt) {
        return new PackageSummary(packageId, packageType, status, documentCount, createdAt, updatedAt);
    }
}
