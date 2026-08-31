package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.PackageExportRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataPackageExportRecordRepository extends JpaRepository<PackageExportRecordEntity, UUID> {
    List<PackageExportRecordEntity> findByPackageIdAndTenantIdOrderByCycleNumberAsc(UUID packageId, UUID tenantId);
}
