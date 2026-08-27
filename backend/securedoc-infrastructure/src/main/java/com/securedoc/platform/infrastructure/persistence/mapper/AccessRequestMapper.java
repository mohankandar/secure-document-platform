package com.securedoc.platform.infrastructure.persistence.mapper;

import com.securedoc.platform.domain.identity.AccessRequestId;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.identity.VaultEntryId;
import com.securedoc.platform.domain.vault.AccessRequest;
import com.securedoc.platform.domain.vault.AccessRequestStatus;
import com.securedoc.platform.infrastructure.persistence.entity.AccessRequestEntity;

public final class AccessRequestMapper {

    private AccessRequestMapper() {
    }

    public static AccessRequest toDomain(AccessRequestEntity entity) {
        return AccessRequest.reconstitute(
                AccessRequestId.of(entity.getId()),
                TenantId.of(entity.getTenantId()),
                VaultEntryId.of(entity.getVaultEntryId()),
                ArtifactId.of(entity.getArtifactId()),
                ActorId.of(entity.getRequestedBy()),
                entity.getJustification(),
                entity.getRequestedAt(),
                entity.getExpiresAt(),
                AccessRequestStatus.valueOf(entity.getStatus()),
                entity.getDecidedBy() != null ? ActorId.of(entity.getDecidedBy()) : null,
                entity.getDecisionReason(),
                entity.getDecidedAt()
        );
    }

    public static AccessRequestEntity toNewEntity(AccessRequest request) {
        AccessRequestEntity entity = new AccessRequestEntity();
        entity.setId(request.id().value());
        entity.setTenantId(request.tenantId().value());
        entity.setVaultEntryId(request.vaultEntryId().value());
        entity.setArtifactId(request.artifactId().value());
        entity.setRequestedBy(request.requestedBy().value());
        entity.setJustification(request.justification());
        entity.setStatus(request.status().name());
        entity.setRequestedAt(request.requestedAt());
        entity.setExpiresAt(request.expiresAt());
        entity.setDecidedBy(request.decidedBy().map(ActorId::value).orElse(null));
        entity.setDecisionReason(request.decisionReason().orElse(null));
        entity.setDecidedAt(request.decidedAt().orElse(null));
        return entity;
    }

    public static void updateEntity(AccessRequest request, AccessRequestEntity entity) {
        entity.setStatus(request.status().name());
        entity.setDecidedBy(request.decidedBy().map(ActorId::value).orElse(null));
        entity.setDecisionReason(request.decisionReason().orElse(null));
        entity.setDecidedAt(request.decidedAt().orElse(null));
    }
}
