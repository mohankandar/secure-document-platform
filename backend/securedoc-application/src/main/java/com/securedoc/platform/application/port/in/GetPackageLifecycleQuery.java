package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.lifecycle.result.PackageLifecycleSummary;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

public interface GetPackageLifecycleQuery {
    PackageLifecycleSummary execute(PackageId packageId, TenantId tenantId);
}
