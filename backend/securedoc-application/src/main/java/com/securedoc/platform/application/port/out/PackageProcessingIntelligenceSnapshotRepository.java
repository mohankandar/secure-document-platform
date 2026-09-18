package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.intelligence.PackageProcessingIntelligenceSnapshot;

import java.util.Optional;

public interface PackageProcessingIntelligenceSnapshotRepository {
    Optional<PackageProcessingIntelligenceSnapshot> findByTenantIdAndPackageIdAndProcessingCycle(
            TenantId tenantId, PackageId packageId, int processingCycle);

    Optional<PackageProcessingIntelligenceSnapshot> findLatestByTenantIdAndPackageId(
            TenantId tenantId, PackageId packageId);

    /** Persist once for the cycle and return the authoritative stored snapshot. */
    PackageProcessingIntelligenceSnapshot saveIfAbsent(PackageProcessingIntelligenceSnapshot snapshot);
}
