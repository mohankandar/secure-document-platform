package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for persisting and querying artifacts.
 */
public interface ArtifactRepository {

    void save(Artifact artifact);

    Optional<Artifact> findByIdAndTenantId(ArtifactId id, TenantId tenantId);

    List<Artifact> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId);
}
