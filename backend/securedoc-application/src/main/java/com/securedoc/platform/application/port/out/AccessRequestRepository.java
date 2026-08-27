package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.AccessRequestId;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.identity.VaultEntryId;
import com.securedoc.platform.domain.vault.AccessRequest;
import com.securedoc.platform.domain.vault.AccessRequestStatus;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for persisting and querying access requests.
 * All query methods are tenant-scoped.
 */
public interface AccessRequestRepository {

    void save(AccessRequest request);

    Optional<AccessRequest> findByIdAndTenantId(AccessRequestId id, TenantId tenantId);

    List<AccessRequest> findByTenantId(TenantId tenantId);

    List<AccessRequest> findByVaultEntryIdAndTenantId(VaultEntryId vaultEntryId, TenantId tenantId);

    List<AccessRequest> findApprovedByArtifactIdAndRequesterAndTenantId(
            ArtifactId artifactId, ActorId requesterId, TenantId tenantId);

    List<AccessRequest> findByStatusAndTenantId(AccessRequestStatus status, TenantId tenantId);
}
