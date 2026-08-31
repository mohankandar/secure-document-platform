package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.DocumentPackageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataDocumentPackageRepository extends JpaRepository<DocumentPackageEntity, UUID> {

    Optional<DocumentPackageEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    List<DocumentPackageEntity> findByTenantId(UUID tenantId, Pageable pageable);

    long countByTenantId(UUID tenantId);

    Optional<DocumentPackageEntity> findByTenantIdAndIntakeFingerprint(UUID tenantId, String intakeFingerprint);

    List<DocumentPackageEntity> findByStatus(String status, Pageable pageable);
}
