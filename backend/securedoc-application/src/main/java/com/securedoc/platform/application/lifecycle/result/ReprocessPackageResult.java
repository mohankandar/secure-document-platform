package com.securedoc.platform.application.lifecycle.result;

import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.identity.PackageId;

public record ReprocessPackageResult(PackageId packageId, PackageStatus status, int nextExportCycle) {
}
