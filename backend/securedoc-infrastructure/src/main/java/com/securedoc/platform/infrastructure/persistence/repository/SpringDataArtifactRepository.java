package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.ArtifactEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataArtifactRepository extends JpaRepository<ArtifactEntity, UUID> {

    Optional<ArtifactEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    List<ArtifactEntity> findByDocumentId(UUID documentId);

    List<ArtifactEntity> findByDocumentIdAndTenantId(UUID documentId, UUID tenantId);
}
