package com.securedoc.platform.application.operations.result;

import com.securedoc.platform.domain.identity.PackageId;

import java.time.Instant;

public record HidePackageFromDashboardResult(PackageId packageId, Instant hiddenAt) {
}
