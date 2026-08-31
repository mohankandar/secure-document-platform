package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.entity.DocumentPackageEntity;
import com.securedoc.platform.infrastructure.persistence.mapper.DocumentMapper;
import com.securedoc.platform.infrastructure.persistence.mapper.DocumentPackageMapper;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataDocumentPackageRepository;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataDocumentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class JpaPackageRepositoryAdapter implements PackageRepository {

    private final SpringDataDocumentPackageRepository packageRepo;
    private final SpringDataDocumentRepository documentRepo;

    public JpaPackageRepositoryAdapter(SpringDataDocumentPackageRepository packageRepo,
                                        SpringDataDocumentRepository documentRepo) {
        this.packageRepo = packageRepo;
        this.documentRepo = documentRepo;
    }

    @Override
    public void save(DocumentPackage documentPackage) {
        Optional<DocumentPackageEntity> existing = packageRepo.findByIdAndTenantId(
                documentPackage.id().value(), documentPackage.tenantId().value());
        if (existing.isPresent()) {
            DocumentPackageMapper.updateEntity(documentPackage, existing.get());
            packageRepo.save(existing.get());
        } else {
            packageRepo.save(DocumentPackageMapper.toNewEntity(documentPackage));
        }
    }

    @Override
    public Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId tenantId) {
        return packageRepo.findByIdAndTenantId(id.value(), tenantId.value())
                .map(this::toDomainWithDocuments);
    }

    @Override
    public List<DocumentPackage> findByTenantId(TenantId tenantId, int offset, int limit) {
        int page = offset / Math.max(limit, 1);
        return packageRepo.findByTenantId(tenantId.value(), PageRequest.of(page, limit))
                .stream()
                .map(this::toDomainWithDocuments)
                .toList();
    }

    @Override
    public long countByTenantId(TenantId tenantId) {
        return packageRepo.countByTenantId(tenantId.value());
    }


    @Override
    public Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(TenantId tenantId, String fingerprint) {
        return packageRepo.findByTenantIdAndIntakeFingerprint(tenantId.value(), fingerprint)
                .map(this::toDomainWithDocuments);
    }

    @Override
    public List<DocumentPackage> findByStatus(PackageStatus status, int limit) {
        return packageRepo.findByStatus(status.name(), PageRequest.of(0, limit))
                .stream()
                .map(this::toDomainWithDocuments)
                .toList();
    }

    private DocumentPackage toDomainWithDocuments(DocumentPackageEntity entity) {
        List<Document> documents = documentRepo.findByPackageIdAndTenantId(entity.getId(), entity.getTenantId())
                .stream()
                .map(DocumentMapper::toDomain)
                .toList();
        return DocumentPackageMapper.toDomain(entity, documents);
    }
}
