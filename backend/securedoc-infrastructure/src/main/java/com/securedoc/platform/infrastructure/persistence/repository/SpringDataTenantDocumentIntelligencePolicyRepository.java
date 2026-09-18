package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.TenantDocumentIntelligencePolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SpringDataTenantDocumentIntelligencePolicyRepository extends JpaRepository<TenantDocumentIntelligencePolicyEntity, UUID> {
}
