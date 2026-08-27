package com.securedoc.platform.domain.vault;

import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.identity.VaultEntryId;
import com.securedoc.platform.domain.vo.Checksum;

import java.time.Instant;
import java.util.Objects;

/**
 * Entity representing a sealed, immutable entry in the secure vault.
 * Each vault entry tracks a single artifact (typically REDACTED or ORIGINAL)
 * for a specific document within a package.
 * Once registered, a vault entry cannot be modified — only its access can be controlled.
 */
public final class VaultEntry {

    private final VaultEntryId id;
    private final TenantId tenantId;
    private final PackageId packageId;
    private final DocumentId documentId;
    private final ArtifactId artifactId;
    private final ArtifactType artifactType;
    private final Checksum integrityChecksum;
    private final RetentionPolicy retentionPolicy;
    private final Instant registeredAt;
    private boolean sealed;
    private Instant sealedAt;

    private VaultEntry(VaultEntryId id, TenantId tenantId, PackageId packageId,
                       DocumentId documentId, ArtifactId artifactId, ArtifactType artifactType,
                       Checksum integrityChecksum, RetentionPolicy retentionPolicy,
                       Instant registeredAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.packageId = packageId;
        this.documentId = documentId;
        this.artifactId = artifactId;
        this.artifactType = artifactType;
        this.integrityChecksum = integrityChecksum;
        this.retentionPolicy = retentionPolicy;
        this.registeredAt = registeredAt;
        this.sealed = false;
    }

    public static VaultEntry register(TenantId tenantId, PackageId packageId,
                                       DocumentId documentId, ArtifactId artifactId,
                                       ArtifactType artifactType,
                                       Checksum integrityChecksum, RetentionPolicy retentionPolicy) {
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(artifactId, "ArtifactId must not be null");
        Objects.requireNonNull(artifactType, "ArtifactType must not be null");
        Objects.requireNonNull(integrityChecksum, "Integrity checksum must not be null");
        Objects.requireNonNull(retentionPolicy, "Retention policy must not be null");

        return new VaultEntry(VaultEntryId.generate(), tenantId, packageId, documentId,
                artifactId, artifactType, integrityChecksum, retentionPolicy, Instant.now());
    }

    public static VaultEntry reconstitute(VaultEntryId id, TenantId tenantId, PackageId packageId,
                                           DocumentId documentId, ArtifactId artifactId,
                                           ArtifactType artifactType,
                                           Checksum integrityChecksum, RetentionPolicy retentionPolicy,
                                           Instant registeredAt, boolean sealed, Instant sealedAt) {
        VaultEntry entry = new VaultEntry(id, tenantId, packageId, documentId, artifactId,
                artifactType, integrityChecksum, retentionPolicy, registeredAt);
        entry.sealed = sealed;
        entry.sealedAt = sealedAt;
        return entry;
    }

    /**
     * Seals the vault entry, making it immutable and marking the seal timestamp.
     */
    public void seal() {
        if (sealed) {
            throw new IllegalStateException("Vault entry is already sealed");
        }
        this.sealed = true;
        this.sealedAt = Instant.now();
    }

    /**
     * Verifies that the given checksum matches the registered integrity checksum.
     */
    public boolean verifyIntegrity(Checksum currentChecksum) {
        return integrityChecksum.matches(currentChecksum);
    }

    // --- Accessors ---

    public VaultEntryId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public PackageId packageId() {
        return packageId;
    }

    public DocumentId documentId() {
        return documentId;
    }

    public ArtifactId artifactId() {
        return artifactId;
    }

    public ArtifactType artifactType() {
        return artifactType;
    }

    public Checksum integrityChecksum() {
        return integrityChecksum;
    }

    public RetentionPolicy retentionPolicy() {
        return retentionPolicy;
    }

    public Instant registeredAt() {
        return registeredAt;
    }

    public boolean isSealed() {
        return sealed;
    }

    public Instant sealedAt() {
        return sealedAt;
    }
}
