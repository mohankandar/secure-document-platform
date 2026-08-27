package com.securedoc.platform.application.vault.result;

import com.securedoc.platform.domain.identity.AccessRequestId;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.identity.VaultEntryId;
import com.securedoc.platform.domain.vault.AccessRequest;
import com.securedoc.platform.domain.vault.AccessRequestStatus;

import java.time.Instant;

/**
 * Result of an access request operation.
 */
public record ArtifactAccessRequestResult(
        AccessRequestId id,
        TenantId tenantId,
        VaultEntryId vaultEntryId,
        ArtifactId artifactId,
        ActorId requestedBy,
        AccessRequestStatus status,
        Instant requestedAt,
        Instant expiresAt,
        Instant decidedAt
) {
    public static ArtifactAccessRequestResult from(AccessRequest request) {
        return new ArtifactAccessRequestResult(
                request.id(), request.tenantId(), request.vaultEntryId(),
                request.artifactId(), request.requestedBy(), request.status(),
                request.requestedAt(), request.expiresAt(),
                request.decidedAt().orElse(null));
    }
}
