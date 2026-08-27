package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.FindingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataFindingRepository extends JpaRepository<FindingEntity, UUID> {

    Optional<FindingEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    List<FindingEntity> findByDocumentIdAndTenantId(UUID documentId, UUID tenantId);
}
