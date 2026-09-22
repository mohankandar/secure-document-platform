package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.PackageLifecycleEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataPackageLifecycleRepository extends JpaRepository<PackageLifecycleEntity, UUID> {
    Optional<PackageLifecycleEntity> findByPackageIdAndTenantId(UUID packageId, UUID tenantId);

    @Query("select p from PackageLifecycleEntity p where p.purgedAt is null and p.redactedPurgedAt is null and p.redactedDeleteAt is not null and p.redactedDeleteAt <= :now order by p.redactedDeleteAt asc")
    List<PackageLifecycleEntity> findDueRedacted(@Param("now") Instant now, Pageable pageable);

    @Query("select p from PackageLifecycleEntity p where p.purgedAt is null and p.originalPurgedAt is null and ((p.successfulExportCycles > 0 and p.originalDeleteAt is not null and p.originalDeleteAt <= :now) or (p.successfulExportCycles = 0 and p.technicalHoldUntil is not null and p.technicalHoldUntil <= :now)) order by p.updatedAt asc")
    List<PackageLifecycleEntity> findDueFinal(@Param("now") Instant now, Pageable pageable);

    @Query("select l from PackageLifecycleEntity l, DocumentPackageEntity p "
            + "where p.id = l.packageId and p.tenantId = l.tenantId "
            + "and p.dashboardHiddenAt is null and l.purgedAt is not null "
            + "and ((p.status = 'COMPLETED' and l.successfulExportCycles > 0) "
            + "or (p.status = 'FAILED' and l.successfulExportCycles = 0)) "
            + "order by l.purgedAt asc")
    List<PackageLifecycleEntity> findPurgedPackagesNeedingDashboardHide(Pageable pageable);
}
