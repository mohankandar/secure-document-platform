package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.TenantCapabilityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataTenantCapabilityRepository extends JpaRepository<TenantCapabilityEntity, UUID> {

    List<TenantCapabilityEntity> findByTenantId(UUID tenantId);

    void deleteByTenantId(UUID tenantId);
}
