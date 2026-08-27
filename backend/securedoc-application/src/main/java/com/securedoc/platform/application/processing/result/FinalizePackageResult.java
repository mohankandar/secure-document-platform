package com.securedoc.platform.application.processing.result;

import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.identity.PackageId;

/**
 * Result of package finalization.
 */
public record FinalizePackageResult(
        PackageId packageId,
        PackageStatus status
) {
    public static FinalizePackageResult of(PackageId packageId, PackageStatus status) {
        return new FinalizePackageResult(packageId, status);
    }
}
