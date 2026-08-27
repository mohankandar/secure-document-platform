package com.securedoc.platform.domain.artifact;

import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.MimeType;

import java.time.Instant;
import java.util.Objects;

/**
 * Entity representing a processing artifact generated from a document.
 * Artifacts are outputs of the processing pipeline (OCR text, redacted versions, etc.).
 */
public final class Artifact {

    private final ArtifactId id;
    private final TenantId tenantId;
    private final DocumentId documentId;
    private final ArtifactType artifactType;
    private final MimeType mimeType;
    private final long sizeBytes;
    private final Checksum checksum;
    private final Instant createdAt;
    private ArtifactStatus status;
    private ArtifactStorageReference storageReference;
    private Instant updatedAt;

    private Artifact(ArtifactId id, TenantId tenantId, DocumentId documentId, ArtifactType artifactType,
                     MimeType mimeType, long sizeBytes, Checksum checksum, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.documentId = documentId;
        this.artifactType = artifactType;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.createdAt = createdAt;
        this.status = ArtifactStatus.GENERATING;
        this.updatedAt = createdAt;
    }

    public static Artifact create(TenantId tenantId, DocumentId documentId, ArtifactType artifactType,
                                   MimeType mimeType, long sizeBytes, Checksum checksum) {
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(artifactType, "ArtifactType must not be null");
        Objects.requireNonNull(mimeType, "MimeType must not be null");
        Objects.requireNonNull(checksum, "Checksum must not be null");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Size bytes must be >= 0, got: " + sizeBytes);
        }

        return new Artifact(ArtifactId.generate(), tenantId, documentId, artifactType,
                mimeType, sizeBytes, checksum, Instant.now());
    }

    public static Artifact reconstitute(ArtifactId id, TenantId tenantId, DocumentId documentId,
                                         ArtifactType artifactType, MimeType mimeType, long sizeBytes,
                                         Checksum checksum, Instant createdAt, ArtifactStatus status,
                                         ArtifactStorageReference storageReference, Instant updatedAt) {
        Artifact artifact = new Artifact(id, tenantId, documentId, artifactType,
                mimeType, sizeBytes, checksum, createdAt);
        artifact.status = status;
        artifact.storageReference = storageReference;
        artifact.updatedAt = updatedAt;
        return artifact;
    }

    public void markAvailable(ArtifactStorageReference reference) {
        Objects.requireNonNull(reference, "Storage reference must not be null");
        if (status != ArtifactStatus.GENERATING) {
            throw new IllegalStateException("Cannot mark available artifact in status: " + status);
        }
        this.storageReference = reference;
        this.status = ArtifactStatus.AVAILABLE;
        this.updatedAt = Instant.now();
    }

    public void markFailed() {
        if (status != ArtifactStatus.GENERATING) {
            throw new IllegalStateException("Cannot mark failed artifact in status: " + status);
        }
        this.status = ArtifactStatus.FAILED;
        this.updatedAt = Instant.now();
    }

    public void delete() {
        if (status == ArtifactStatus.DELETED) {
            throw new IllegalStateException("Artifact is already deleted");
        }
        this.status = ArtifactStatus.DELETED;
        this.updatedAt = Instant.now();
    }

    // --- Accessors ---

    public ArtifactId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public DocumentId documentId() {
        return documentId;
    }

    public ArtifactType artifactType() {
        return artifactType;
    }

    public MimeType mimeType() {
        return mimeType;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public Checksum checksum() {
        return checksum;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public ArtifactStatus status() {
        return status;
    }

    public ArtifactStorageReference storageReference() {
        return storageReference;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean isAvailable() {
        return status == ArtifactStatus.AVAILABLE;
    }
}
