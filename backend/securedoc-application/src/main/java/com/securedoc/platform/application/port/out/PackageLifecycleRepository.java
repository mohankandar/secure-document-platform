package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.lifecycle.PackageLifecycle;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PackageLifecycleRepository {
    Optional<PackageLifecycle> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId);
    void save(PackageLifecycle lifecycle);
    List<PackageLifecycle> findDueRedactedPurge(Instant now, int limit);
    List<PackageLifecycle> findDueFinalPurge(Instant now, int limit);

    /** Reconciles Dashboard visibility for any package whose final processing retention already completed. */
    default List<PackageLifecycle> findPurgedPackagesNeedingDashboardHide(int limit) {
        return findPurgedCompletedExportsNeedingDashboardHide(limit);
    }

    /** Compatibility hook retained for focused/unit fakes created by the frozen FIX1/FIX2 baseline. */
    @Deprecated
    default List<PackageLifecycle> findPurgedCompletedExportsNeedingDashboardHide(int limit) {
        return List.of();
    }
}
