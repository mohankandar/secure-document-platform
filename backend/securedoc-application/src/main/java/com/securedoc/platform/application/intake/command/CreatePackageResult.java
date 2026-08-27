package com.securedoc.platform.application.intake.command;

import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.identity.PackageId;

import java.time.Instant;
import java.util.Objects;

/**
 * Result of a successful package creation.
 */
public record CreatePackageResult(
        PackageId packageId,
        PackageStatus status,
        Instant createdAt
) {

    public CreatePackageResult {
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(status, "Status must not be null");
        Objects.requireNonNull(createdAt, "CreatedAt must not be null");
    }

    public static CreatePackageResult of(PackageId packageId, PackageStatus status, Instant createdAt) {
        return new CreatePackageResult(packageId, status, createdAt);
    }
}
