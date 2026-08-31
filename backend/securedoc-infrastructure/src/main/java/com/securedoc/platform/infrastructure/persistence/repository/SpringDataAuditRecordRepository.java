package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.AuditRecordEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface SpringDataAuditRecordRepository extends JpaRepository<AuditRecordEntity, UUID>,
        JpaSpecificationExecutor<AuditRecordEntity> {

    List<AuditRecordEntity> findByTenantIdOrderByOccurredAtDesc(UUID tenantId, Pageable pageable);
}
