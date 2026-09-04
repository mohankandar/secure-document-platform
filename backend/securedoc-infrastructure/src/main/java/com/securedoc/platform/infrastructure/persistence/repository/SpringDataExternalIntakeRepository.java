package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.ExternalIntakeEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataExternalIntakeRepository extends JpaRepository<ExternalIntakeEntity, UUID> {
    Optional<ExternalIntakeEntity> findByPackageIdAndTenantId(UUID packageId, UUID tenantId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from ExternalIntakeEntity e where e.packageId = :packageId and e.tenantId = :tenantId")
    Optional<ExternalIntakeEntity> findForUpdate(UUID packageId, UUID tenantId);
    Optional<ExternalIntakeEntity> findFirstByTenantIdAndIntegrationConnectionIdAndExternalSourcePackageIdAndSourceRevisionOrderByAttemptNumberDesc(
            UUID tenantId, UUID integrationConnectionId, String externalSourcePackageId, String sourceRevision);
    List<ExternalIntakeEntity> findByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(String status, Instant now, Pageable pageable);
    List<ExternalIntakeEntity> findByStatusAndCleanupCompletedAtIsNullOrderByExpiredAtAsc(String status, Pageable pageable);
}
