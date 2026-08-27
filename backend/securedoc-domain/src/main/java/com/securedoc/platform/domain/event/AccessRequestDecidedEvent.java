package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.identity.AccessRequestId;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.identity.VaultEntryId;
import com.securedoc.platform.domain.vault.AccessRequestStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Event raised when a vault access request is decided (approved/denied).
 */
public record AccessRequestDecidedEvent(
        AccessRequestId requestId,
        VaultEntryId vaultEntryId,
        ArtifactId artifactId,
        TenantId tenantId,
        ActorId decidedBy,
        AccessRequestStatus decision,
        CorrelationId correlationId,
        Instant occurredAt
) implements DomainEvent {

    public AccessRequestDecidedEvent {
        Objects.requireNonNull(requestId, "RequestId must not be null");
        Objects.requireNonNull(vaultEntryId, "VaultEntryId must not be null");
        Objects.requireNonNull(artifactId, "ArtifactId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(decidedBy, "DecidedBy must not be null");
        Objects.requireNonNull(decision, "Decision must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt must not be null");
    }

    public static AccessRequestDecidedEvent of(AccessRequestId requestId, VaultEntryId vaultEntryId,
                                                ArtifactId artifactId, TenantId tenantId,
                                                ActorId decidedBy, AccessRequestStatus decision,
                                                CorrelationId correlationId) {
        return new AccessRequestDecidedEvent(requestId, vaultEntryId, artifactId, tenantId,
                decidedBy, decision, correlationId, Instant.now());
    }

    @Override
    public String eventType() {
        return "AccessRequestDecided";
    }
}
