package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.DashboardColumnSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataDashboardColumnSettingRepository extends JpaRepository<DashboardColumnSettingEntity, UUID> {
    List<DashboardColumnSettingEntity> findByTenantIdOrderByDisplayOrderAsc(UUID tenantId);
    void deleteByTenantId(UUID tenantId);
}
