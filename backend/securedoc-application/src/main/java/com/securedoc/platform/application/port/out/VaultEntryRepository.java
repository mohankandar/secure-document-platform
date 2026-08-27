package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.identity.VaultEntryId;
import com.securedoc.platform.domain.vault.VaultEntry;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for persisting and querying vault entries.
 * All query methods are tenant-scoped.
 */
public interface VaultEntryRepository {

    void save(VaultEntry entry);

    Optional<VaultEntry> findByIdAndTenantId(VaultEntryId id, TenantId tenantId);

    List<VaultEntry> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId);

    List<VaultEntry> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId);

    Optional<VaultEntry> findByArtifactIdAndTenantId(ArtifactId artifactId, TenantId tenantId);

    Optional<VaultEntry> findByDocumentIdAndArtifactTypeAndTenantId(
            DocumentId documentId, ArtifactType artifactType, TenantId tenantId);
}
