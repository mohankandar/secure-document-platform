package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.ProcessingIncidentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataProcessingIncidentRepository extends JpaRepository<ProcessingIncidentEntity, UUID> {
    Optional<ProcessingIncidentEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<ProcessingIncidentEntity> findFirstByTenantIdAndPackageIdOrderByLastObservedAtDesc(UUID tenantId, UUID packageId);
    List<ProcessingIncidentEntity> findTop50ByTenantIdAndPackageIdOrderByLastObservedAtDesc(UUID tenantId, UUID packageId);
    List<ProcessingIncidentEntity> findTop100ByTenantIdOrderByLastObservedAtDesc(UUID tenantId);
}
