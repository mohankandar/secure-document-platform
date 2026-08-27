package com.securedoc.platform.infrastructure.persistence.mapper;

import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.identity.VaultEntryId;
import com.securedoc.platform.domain.vault.RetentionPolicy;
import com.securedoc.platform.domain.vault.VaultEntry;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.infrastructure.persistence.entity.VaultEntryEntity;

import java.time.Duration;

public final class VaultEntryMapper {

    private VaultEntryMapper() {
    }

    public static VaultEntry toDomain(VaultEntryEntity entity) {
        Duration minRetention = Duration.ofDays(entity.getRetentionMinDays());
        Duration maxRetention = entity.getRetentionMaxDays() != null
                ? Duration.ofDays(entity.getRetentionMaxDays()) : null;
        RetentionPolicy policy = RetentionPolicy.of(minRetention, maxRetention, entity.isLegalHold());

        return VaultEntry.reconstitute(
                VaultEntryId.of(entity.getId()),
                TenantId.of(entity.getTenantId()),
                PackageId.of(entity.getPackageId()),
                DocumentId.of(entity.getDocumentId()),
                ArtifactId.of(entity.getArtifactId()),
                ArtifactType.valueOf(entity.getArtifactType()),
                Checksum.of(entity.getChecksumAlgorithm(), entity.getChecksumValue()),
                policy,
                entity.getRegisteredAt(),
                entity.isSealed(),
                entity.getSealedAt()
        );
    }

    public static VaultEntryEntity toNewEntity(VaultEntry entry) {
        VaultEntryEntity entity = new VaultEntryEntity();
        entity.setId(entry.id().value());
        entity.setTenantId(entry.tenantId().value());
        entity.setPackageId(entry.packageId().value());
        entity.setDocumentId(entry.documentId().value());
        entity.setArtifactId(entry.artifactId().value());
        entity.setArtifactType(entry.artifactType().name());
        entity.setChecksumAlgorithm(entry.integrityChecksum().algorithm());
        entity.setChecksumValue(entry.integrityChecksum().value());
        entity.setRetentionMinDays(entry.retentionPolicy().minimumRetention().toDays());
        entity.setRetentionMaxDays(
                entry.retentionPolicy().maximumRetentionOpt().map(Duration::toDays).orElse(null));
        entity.setLegalHold(entry.retentionPolicy().legalHold());
        entity.setSealed(entry.isSealed());
        entity.setSealedAt(entry.sealedAt());
        entity.setRegisteredAt(entry.registeredAt());
        return entity;
    }

    public static void updateEntity(VaultEntry entry, VaultEntryEntity entity) {
        entity.setSealed(entry.isSealed());
        entity.setSealedAt(entry.sealedAt());
    }
}
