package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.BusinessRecordIdentifierEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataBusinessRecordIdentifierRepository extends JpaRepository<BusinessRecordIdentifierEntity, UUID> {
    List<BusinessRecordIdentifierEntity> findByBusinessRecordIdAndTenantId(UUID businessRecordId, UUID tenantId);
    Optional<BusinessRecordIdentifierEntity> findByTenantIdAndDefinitionIdAndNormalizedValue(UUID tenantId, UUID definitionId, String normalizedValue);
}
