package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.AccessRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataAccessRequestRepository extends JpaRepository<AccessRequestEntity, UUID> {

    Optional<AccessRequestEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    List<AccessRequestEntity> findByTenantId(UUID tenantId);

    List<AccessRequestEntity> findByVaultEntryIdAndTenantId(UUID vaultEntryId, UUID tenantId);

    List<AccessRequestEntity> findByStatusAndTenantId(String status, UUID tenantId);

    List<AccessRequestEntity> findByArtifactIdAndRequestedByAndStatusAndTenantId(
            UUID artifactId, UUID requestedBy, String status, UUID tenantId);
}
