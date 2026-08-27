package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.identity.VaultEntryId;

import java.time.Instant;
import java.util.Objects;

/**
 * Event raised when an artifact is accessed/downloaded from the vault.
 * Contains IDs and artifact type only — no file paths or content.
 */
public record ArtifactAccessedEvent(
        VaultEntryId vaultEntryId,
        ArtifactId artifactId,
        ArtifactType artifactType,
        DocumentId documentId,
        TenantId tenantId,
        ActorId accessedBy,
        CorrelationId correlationId,
        Instant occurredAt
) implements DomainEvent {

    public ArtifactAccessedEvent {
        Objects.requireNonNull(vaultEntryId, "VaultEntryId must not be null");
        Objects.requireNonNull(artifactId, "ArtifactId must not be null");
        Objects.requireNonNull(artifactType, "ArtifactType must not be null");
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(accessedBy, "AccessedBy must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt must not be null");
    }

    public static ArtifactAccessedEvent of(VaultEntryId vaultEntryId, ArtifactId artifactId,
                                            ArtifactType artifactType, DocumentId documentId,
                                            TenantId tenantId, ActorId accessedBy,
                                            CorrelationId correlationId) {
        return new ArtifactAccessedEvent(vaultEntryId, artifactId, artifactType, documentId,
                tenantId, accessedBy, correlationId, Instant.now());
    }

    @Override
    public String eventType() {
        return "ArtifactAccessed";
    }
}
