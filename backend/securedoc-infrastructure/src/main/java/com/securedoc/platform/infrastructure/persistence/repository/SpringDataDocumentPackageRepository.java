package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.DocumentPackageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataDocumentPackageRepository extends JpaRepository<DocumentPackageEntity, UUID> {

    Optional<DocumentPackageEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Task 0039: a row lock on the case, held until the caller's transaction ends (which it must have). Returns the id
     * when the case exists for this tenant. Only the id is read, so no entity is loaded before the lock is held.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "SELECT CAST(id AS varchar) FROM document_packages WHERE id = :id AND tenant_id = :tenantId FOR UPDATE",
            nativeQuery = true)
    Optional<String> lockByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    List<DocumentPackageEntity> findByTenantId(UUID tenantId, Pageable pageable);

    long countByTenantId(UUID tenantId);

    Optional<DocumentPackageEntity> findByTenantIdAndIntakeFingerprint(UUID tenantId, String intakeFingerprint);

    List<DocumentPackageEntity> findByStatus(String status, Pageable pageable);
}
