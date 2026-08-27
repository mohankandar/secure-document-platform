package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.identity.VaultEntryId;

import java.time.Instant;
import java.util.Objects;

/**
 * Event raised when a vault entry is registered for a processed artifact.
 * Contains IDs and safe metadata only — no file paths or raw content.
 */
public record VaultEntryCreatedEvent(
        VaultEntryId vaultEntryId,
        TenantId tenantId,
        PackageId packageId,
        DocumentId documentId,
        ArtifactId artifactId,
        ArtifactType artifactType,
        CorrelationId correlationId,
        Instant occurredAt
) implements DomainEvent {

    public VaultEntryCreatedEvent {
        Objects.requireNonNull(vaultEntryId, "VaultEntryId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(artifactId, "ArtifactId must not be null");
        Objects.requireNonNull(artifactType, "ArtifactType must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
        Objects.requireNonNull(occurredAt, "OccurredAt must not be null");
    }

    public static VaultEntryCreatedEvent of(VaultEntryId vaultEntryId, TenantId tenantId,
                                             PackageId packageId, DocumentId documentId,
                                             ArtifactId artifactId, ArtifactType artifactType,
                                             CorrelationId correlationId) {
        return new VaultEntryCreatedEvent(vaultEntryId, tenantId, packageId, documentId,
                artifactId, artifactType, correlationId, Instant.now());
    }

    @Override
    public String eventType() {
        return "VaultEntryCreated";
    }
}
