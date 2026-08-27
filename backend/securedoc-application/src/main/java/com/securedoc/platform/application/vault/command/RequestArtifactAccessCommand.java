package com.securedoc.platform.application.vault.command;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.identity.VaultEntryId;

import java.time.Duration;
import java.util.Objects;

/**
 * Command to request access to a restricted (original) artifact.
 */
public record RequestArtifactAccessCommand(
        TenantId tenantId,
        VaultEntryId vaultEntryId,
        ArtifactId artifactId,
        ActorId requesterId,
        String justification,
        Duration accessDuration,
        CorrelationId correlationId
) {
    public RequestArtifactAccessCommand {
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(vaultEntryId, "VaultEntryId must not be null");
        Objects.requireNonNull(artifactId, "ArtifactId must not be null");
        Objects.requireNonNull(requesterId, "RequesterId must not be null");
        Objects.requireNonNull(justification, "Justification must not be null");
        Objects.requireNonNull(accessDuration, "AccessDuration must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        if (justification.isBlank()) {
            throw new IllegalArgumentException("Justification must not be blank");
        }
        if (accessDuration.isNegative() || accessDuration.isZero()) {
            throw new IllegalArgumentException("Access duration must be positive");
        }
    }

    public static RequestArtifactAccessCommand of(TenantId tenantId, VaultEntryId vaultEntryId,
                                                   ArtifactId artifactId, ActorId requesterId,
                                                   String justification, Duration accessDuration,
                                                   CorrelationId correlationId) {
        return new RequestArtifactAccessCommand(tenantId, vaultEntryId, artifactId,
                requesterId, justification, accessDuration, correlationId);
    }
}
