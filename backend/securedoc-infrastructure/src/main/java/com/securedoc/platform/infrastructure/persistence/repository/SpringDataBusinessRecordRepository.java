package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.BusinessRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataBusinessRecordRepository extends JpaRepository<BusinessRecordEntity, UUID> {
    Optional<BusinessRecordEntity> findByIdAndTenantId(UUID id, UUID tenantId);
}
