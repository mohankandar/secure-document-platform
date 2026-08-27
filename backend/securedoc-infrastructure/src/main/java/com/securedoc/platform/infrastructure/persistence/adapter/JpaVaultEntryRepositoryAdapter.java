package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.VaultEntryRepository;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.identity.VaultEntryId;
import com.securedoc.platform.domain.vault.VaultEntry;
import com.securedoc.platform.infrastructure.persistence.entity.VaultEntryEntity;
import com.securedoc.platform.infrastructure.persistence.mapper.VaultEntryMapper;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataVaultEntryRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class JpaVaultEntryRepositoryAdapter implements VaultEntryRepository {

    private final SpringDataVaultEntryRepository jpaRepo;

    public JpaVaultEntryRepositoryAdapter(SpringDataVaultEntryRepository jpaRepo) {
        this.jpaRepo = jpaRepo;
    }

    @Override
    public void save(VaultEntry entry) {
        Optional<VaultEntryEntity> existing = jpaRepo.findByIdAndTenantId(
                entry.id().value(), entry.tenantId().value());
        if (existing.isPresent()) {
            VaultEntryMapper.updateEntity(entry, existing.get());
            jpaRepo.save(existing.get());
        } else {
            jpaRepo.save(VaultEntryMapper.toNewEntity(entry));
        }
    }

    @Override
    public Optional<VaultEntry> findByIdAndTenantId(VaultEntryId id, TenantId tenantId) {
        return jpaRepo.findByIdAndTenantId(id.value(), tenantId.value())
                .map(VaultEntryMapper::toDomain);
    }

    @Override
    public List<VaultEntry> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) {
        return jpaRepo.findByPackageIdAndTenantId(packageId.value(), tenantId.value())
                .stream()
                .map(VaultEntryMapper::toDomain)
                .toList();
    }

    @Override
    public List<VaultEntry> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) {
        return jpaRepo.findByDocumentIdAndTenantId(documentId.value(), tenantId.value())
                .stream()
                .map(VaultEntryMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<VaultEntry> findByArtifactIdAndTenantId(ArtifactId artifactId, TenantId tenantId) {
        return jpaRepo.findByArtifactIdAndTenantId(artifactId.value(), tenantId.value())
                .map(VaultEntryMapper::toDomain);
    }

    @Override
    public Optional<VaultEntry> findByDocumentIdAndArtifactTypeAndTenantId(
            DocumentId documentId, ArtifactType artifactType, TenantId tenantId) {
        return jpaRepo.findByDocumentIdAndArtifactTypeAndTenantId(
                        documentId.value(), artifactType.name(), tenantId.value())
                .map(VaultEntryMapper::toDomain);
    }
}
