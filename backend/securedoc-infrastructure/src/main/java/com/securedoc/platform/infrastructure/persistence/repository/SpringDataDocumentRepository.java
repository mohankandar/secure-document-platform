package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataDocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    Optional<DocumentEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    List<DocumentEntity> findByPackageId(UUID packageId);

    List<DocumentEntity> findByPackageIdAndTenantId(UUID packageId, UUID tenantId);
}
