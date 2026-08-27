package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.entity.DocumentEntity;
import com.securedoc.platform.infrastructure.persistence.mapper.DocumentMapper;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataDocumentRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class JpaDocumentRepositoryAdapter implements DocumentRepository {

    private final SpringDataDocumentRepository jpaRepo;

    public JpaDocumentRepositoryAdapter(SpringDataDocumentRepository jpaRepo) {
        this.jpaRepo = jpaRepo;
    }

    @Override
    public void save(Document document) {
        Optional<DocumentEntity> existing = jpaRepo.findById(document.id().value());
        if (existing.isPresent()) {
            DocumentMapper.updateEntity(document, existing.get());
            jpaRepo.save(existing.get());
        } else {
            jpaRepo.save(DocumentMapper.toNewEntity(document));
        }
    }

    @Override
    public Optional<Document> findById(DocumentId id) {
        return jpaRepo.findById(id.value()).map(DocumentMapper::toDomain);
    }

    @Override
    public Optional<Document> findByIdAndTenantId(DocumentId id, TenantId tenantId) {
        return jpaRepo.findByIdAndTenantId(id.value(), tenantId.value())
                .map(DocumentMapper::toDomain);
    }

    @Override
    public List<Document> findByPackageId(PackageId packageId) {
        return jpaRepo.findByPackageId(packageId.value())
                .stream()
                .map(DocumentMapper::toDomain)
                .toList();
    }

    @Override
    public List<Document> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) {
        return jpaRepo.findByPackageIdAndTenantId(packageId.value(), tenantId.value())
                .stream()
                .map(DocumentMapper::toDomain)
                .toList();
    }
}
