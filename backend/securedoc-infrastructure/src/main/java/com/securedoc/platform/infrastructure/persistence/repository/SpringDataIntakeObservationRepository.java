package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.IntakeObservationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataIntakeObservationRepository extends JpaRepository<IntakeObservationEntity, UUID> {
    Optional<IntakeObservationEntity> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<IntakeObservationEntity> findFirstByTenantIdAndSourceReferenceOrderByUpdatedAtDesc(UUID tenantId, String sourceReference);
    List<IntakeObservationEntity> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId, Pageable pageable);
}
