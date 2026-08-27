package com.securedoc.platform.application.vault.command;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Command to register a processed artifact in the vault.
 */
public record RegisterVaultEntryCommand(
        TenantId tenantId,
        PackageId packageId,
        DocumentId documentId,
        ArtifactId artifactId,
        ActorId actorId,
        CorrelationId correlationId
) {
    public RegisterVaultEntryCommand {
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(artifactId, "ArtifactId must not be null");
        Objects.requireNonNull(actorId, "ActorId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
    }

    public static RegisterVaultEntryCommand of(TenantId tenantId, PackageId packageId,
                                                DocumentId documentId, ArtifactId artifactId,
                                                ActorId actorId, CorrelationId correlationId) {
        return new RegisterVaultEntryCommand(tenantId, packageId, documentId, artifactId,
                actorId, correlationId);
    }
}
