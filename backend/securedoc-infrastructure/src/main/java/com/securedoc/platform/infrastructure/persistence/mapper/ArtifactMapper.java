package com.securedoc.platform.infrastructure.persistence.mapper;

import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStatus;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.MimeType;
import com.securedoc.platform.infrastructure.persistence.entity.ArtifactEntity;

public final class ArtifactMapper {

    private ArtifactMapper() {
    }

    public static Artifact toDomain(ArtifactEntity entity) {
        ArtifactStorageReference ref = null;
        if (entity.getStorageProvider() != null) {
            ref = ArtifactStorageReference.of(
                    entity.getStorageProvider(),
                    entity.getStorageBucket(),
                    entity.getStorageKey()
            );
        }

        return Artifact.reconstitute(
                ArtifactId.of(entity.getId()),
                TenantId.of(entity.getTenantId()),
                DocumentId.of(entity.getDocumentId()),
                ArtifactType.valueOf(entity.getArtifactType()),
                MimeType.of(entity.getMimeType()),
                entity.getSizeBytes(),
                Checksum.of(entity.getChecksumAlgorithm(), entity.getChecksumValue()),
                entity.getCreatedAt(),
                ArtifactStatus.valueOf(entity.getStatus()),
                ref,
                entity.getUpdatedAt()
        );
    }

    public static ArtifactEntity toNewEntity(Artifact artifact) {
        ArtifactEntity entity = new ArtifactEntity();
        entity.setId(artifact.id().value());
        entity.setTenantId(artifact.tenantId().value());
        entity.setDocumentId(artifact.documentId().value());
        entity.setArtifactType(artifact.artifactType().name());
        entity.setStatus(artifact.status().name());
        entity.setMimeType(artifact.mimeType().value());
        entity.setSizeBytes(artifact.sizeBytes());
        entity.setChecksumAlgorithm(artifact.checksum().algorithm());
        entity.setChecksumValue(artifact.checksum().value());
        entity.setCreatedAt(artifact.createdAt());
        entity.setUpdatedAt(artifact.updatedAt());

        ArtifactStorageReference ref = artifact.storageReference();
        if (ref != null) {
            entity.setStorageProvider(ref.storageProvider());
            entity.setStorageBucket(ref.bucket());
            entity.setStorageKey(ref.key());
        }

        return entity;
    }

    /**
     * Updates mutable fields on an existing managed entity from domain state.
     * Preserves the JPA-managed version field for optimistic locking.
     */
    public static void updateEntity(Artifact artifact, ArtifactEntity entity) {
        entity.setStatus(artifact.status().name());
        entity.setUpdatedAt(artifact.updatedAt());

        ArtifactStorageReference ref = artifact.storageReference();
        if (ref != null) {
            entity.setStorageProvider(ref.storageProvider());
            entity.setStorageBucket(ref.bucket());
            entity.setStorageKey(ref.key());
        }
    }
}
