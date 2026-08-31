package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.TenantLifecyclePolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SpringDataTenantLifecyclePolicyRepository extends JpaRepository<TenantLifecyclePolicyEntity, UUID> {
}
