package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.entity.ArtifactEntity;
import com.securedoc.platform.infrastructure.persistence.mapper.ArtifactMapper;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataArtifactRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class JpaArtifactRepositoryAdapter implements ArtifactRepository {

    private final SpringDataArtifactRepository jpaRepo;

    public JpaArtifactRepositoryAdapter(SpringDataArtifactRepository jpaRepo) {
        this.jpaRepo = jpaRepo;
    }

    @Override
    public void save(Artifact artifact) {
        Optional<ArtifactEntity> existing = jpaRepo.findById(artifact.id().value());
        if (existing.isPresent()) {
            ArtifactMapper.updateEntity(artifact, existing.get());
            jpaRepo.save(existing.get());
        } else {
            jpaRepo.save(ArtifactMapper.toNewEntity(artifact));
        }
    }

    @Override
    public Optional<Artifact> findById(ArtifactId id) {
        return jpaRepo.findById(id.value()).map(ArtifactMapper::toDomain);
    }

    @Override
    public Optional<Artifact> findByIdAndTenantId(ArtifactId id, TenantId tenantId) {
        return jpaRepo.findByIdAndTenantId(id.value(), tenantId.value())
                .map(ArtifactMapper::toDomain);
    }

    @Override
    public List<Artifact> findByDocumentId(DocumentId documentId) {
        return jpaRepo.findByDocumentId(documentId.value())
                .stream()
                .map(ArtifactMapper::toDomain)
                .toList();
    }

    @Override
    public List<Artifact> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) {
        return jpaRepo.findByDocumentIdAndTenantId(documentId.value(), tenantId.value())
                .stream()
                .map(ArtifactMapper::toDomain)
                .toList();
    }
}
