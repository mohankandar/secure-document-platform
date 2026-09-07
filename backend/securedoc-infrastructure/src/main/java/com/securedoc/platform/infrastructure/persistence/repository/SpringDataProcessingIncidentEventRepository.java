package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.ProcessingIncidentEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataProcessingIncidentEventRepository extends JpaRepository<ProcessingIncidentEventEntity, UUID> {
    List<ProcessingIncidentEventEntity> findTop100ByTenantIdAndIncidentIdOrderByOccurredAtAsc(UUID tenantId, UUID incidentId);
}
