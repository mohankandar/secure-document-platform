package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.ReviewTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataReviewTaskRepository extends JpaRepository<ReviewTaskEntity, UUID> {

    Optional<ReviewTaskEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    List<ReviewTaskEntity> findByPackageIdAndTenantId(UUID packageId, UUID tenantId);

    List<ReviewTaskEntity> findByStatusAndTenantId(String status, UUID tenantId);

    Optional<ReviewTaskEntity> findFirstByPackageIdAndTenantIdAndStatusIn(UUID packageId, UUID tenantId, List<String> statuses);
}
