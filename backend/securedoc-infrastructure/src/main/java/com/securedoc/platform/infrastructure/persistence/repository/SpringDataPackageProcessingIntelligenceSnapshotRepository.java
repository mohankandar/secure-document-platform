package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.PackageProcessingIntelligenceSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataPackageProcessingIntelligenceSnapshotRepository extends JpaRepository<PackageProcessingIntelligenceSnapshotEntity, UUID> {
    Optional<PackageProcessingIntelligenceSnapshotEntity> findByTenantIdAndPackageIdAndProcessingCycle(
            UUID tenantId, UUID packageId, int processingCycle);

    Optional<PackageProcessingIntelligenceSnapshotEntity> findFirstByTenantIdAndPackageIdOrderByProcessingCycleDesc(
            UUID tenantId, UUID packageId);
}
