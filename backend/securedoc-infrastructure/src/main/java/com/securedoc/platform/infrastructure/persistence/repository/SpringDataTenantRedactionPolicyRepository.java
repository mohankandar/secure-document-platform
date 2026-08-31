package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.TenantRedactionPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SpringDataTenantRedactionPolicyRepository extends JpaRepository<TenantRedactionPolicyEntity, UUID> {
}
