package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.VaultEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataVaultEntryRepository extends JpaRepository<VaultEntryEntity, UUID> {

    Optional<VaultEntryEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    List<VaultEntryEntity> findByPackageIdAndTenantId(UUID packageId, UUID tenantId);

    List<VaultEntryEntity> findByDocumentIdAndTenantId(UUID documentId, UUID tenantId);

    Optional<VaultEntryEntity> findByArtifactIdAndTenantId(UUID artifactId, UUID tenantId);

    Optional<VaultEntryEntity> findByDocumentIdAndArtifactTypeAndTenantId(
            UUID documentId, String artifactType, UUID tenantId);
}
