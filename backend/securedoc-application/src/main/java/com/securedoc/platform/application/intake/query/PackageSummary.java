package com.securedoc.platform.application.intake.query;

import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.PackageId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Lightweight IntakeBatch summary; internal PackageId remains the processing identifier. */
public record PackageSummary(
        PackageId packageId,
        PackageType packageType,
        PackageStatus status,
        List<BusinessIdentifierSummary> businessIdentifiers,
        int documentCount,
        Instant createdAt,
        Instant updatedAt
) {
    public PackageSummary {
        Objects.requireNonNull(packageId); Objects.requireNonNull(packageType); Objects.requireNonNull(status);
        businessIdentifiers = businessIdentifiers == null ? List.of() : List.copyOf(businessIdentifiers);
        Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
    }
}
