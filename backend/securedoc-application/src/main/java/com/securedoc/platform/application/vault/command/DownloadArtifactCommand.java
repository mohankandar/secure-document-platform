package com.securedoc.platform.application.vault.command;

import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Command to download an artifact from the vault.
 */
public record DownloadArtifactCommand(
        TenantId tenantId,
        DocumentId documentId,
        ArtifactType artifactType,
        ActorId actorId,
        CorrelationId correlationId
) {
    public DownloadArtifactCommand {
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(artifactType, "ArtifactType must not be null");
        Objects.requireNonNull(actorId, "ActorId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
    }

    public static DownloadArtifactCommand of(TenantId tenantId, DocumentId documentId,
                                              ArtifactType artifactType, ActorId actorId,
                                              CorrelationId correlationId) {
        return new DownloadArtifactCommand(tenantId, documentId, artifactType, actorId, correlationId);
    }
}
