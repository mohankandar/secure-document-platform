package com.securedoc.platform.application.vault.result;

import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.identity.VaultEntryId;
import com.securedoc.platform.domain.vault.VaultEntry;

import java.time.Instant;

/**
 * Result of registering a vault entry.
 */
public record RegisterVaultEntryResult(
        VaultEntryId id,
        TenantId tenantId,
        PackageId packageId,
        DocumentId documentId,
        ArtifactId artifactId,
        ArtifactType artifactType,
        Instant registeredAt
) {
    public static RegisterVaultEntryResult from(VaultEntry entry) {
        return new RegisterVaultEntryResult(
                entry.id(), entry.tenantId(), entry.packageId(),
                entry.documentId(), entry.artifactId(), entry.artifactType(),
                entry.registeredAt());
    }
}
