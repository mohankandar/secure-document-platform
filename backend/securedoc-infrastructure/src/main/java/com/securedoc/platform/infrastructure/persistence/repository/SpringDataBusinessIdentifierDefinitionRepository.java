package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.BusinessIdentifierDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface SpringDataBusinessIdentifierDefinitionRepository extends JpaRepository<BusinessIdentifierDefinitionEntity, UUID> {
    java.util.Optional<BusinessIdentifierDefinitionEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    List<BusinessIdentifierDefinitionEntity> findByTenantIdAndActiveTrueOrderByDisplayOrderAsc(UUID tenantId);
    List<BusinessIdentifierDefinitionEntity> findByTenantIdOrderByDisplayOrderAsc(UUID tenantId);
    long countByTenantIdAndPrimaryTrueAndActiveTrue(UUID tenantId);
}
