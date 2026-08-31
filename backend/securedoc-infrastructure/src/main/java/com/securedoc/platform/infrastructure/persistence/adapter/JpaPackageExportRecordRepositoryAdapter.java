package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.PackageExportRecordRepository;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.lifecycle.PackageExportRecord;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.infrastructure.persistence.entity.DocumentExportRecordEntity;
import com.securedoc.platform.infrastructure.persistence.entity.PackageExportRecordEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataDocumentExportRecordRepository;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataPackageExportRecordRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class JpaPackageExportRecordRepositoryAdapter implements PackageExportRecordRepository {
    private final SpringDataPackageExportRecordRepository exportRepository;
    private final SpringDataDocumentExportRecordRepository documentRepository;

    public JpaPackageExportRecordRepositoryAdapter(SpringDataPackageExportRecordRepository exportRepository,
                                                   SpringDataDocumentExportRecordRepository documentRepository) {
        this.exportRepository = exportRepository;
        this.documentRepository = documentRepository;
    }

    @Override
    public void save(PackageExportRecord record) {
        PackageExportRecordEntity entity = new PackageExportRecordEntity();
        entity.setId(record.id());
        entity.setTenantId(record.tenantId().value());
        entity.setPackageId(record.packageId().value());
        entity.setCycleNumber(record.cycleNumber());
        entity.setDestinationType(record.destinationType());
        entity.setExportedAt(record.exportedAt());
        entity.setCorrelationId(record.correlationId().value());
        exportRepository.save(entity);

        List<DocumentExportRecordEntity> documents = new ArrayList<>();
        for (PackageExportRecord.ExportedDocument document : record.documents()) {
            DocumentExportRecordEntity de = new DocumentExportRecordEntity();
            de.setId(UUID.randomUUID());
            de.setExportRecordId(record.id());
            de.setTenantId(record.tenantId().value());
            de.setPackageId(record.packageId().value());
            de.setDocumentId(document.documentId().value());
            de.setFileName(document.fileName());
            de.setSizeBytes(document.sizeBytes());
            de.setChecksumAlgorithm(document.checksum().algorithm());
            de.setChecksumValue(document.checksum().value());
            de.setDestinationLocation(document.destinationLocation());
            documents.add(de);
        }
        documentRepository.saveAll(documents);
    }

    @Override
    public List<PackageExportRecord> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) {
        return exportRepository.findByPackageIdAndTenantIdOrderByCycleNumberAsc(packageId.value(), tenantId.value())
                .stream().map(this::toDomain).toList();
    }

    private PackageExportRecord toDomain(PackageExportRecordEntity entity) {
        List<PackageExportRecord.ExportedDocument> documents = documentRepository.findByExportRecordId(entity.getId())
                .stream().map(d -> new PackageExportRecord.ExportedDocument(
                        DocumentId.of(d.getDocumentId()), d.getFileName(), d.getSizeBytes(),
                        Checksum.of(d.getChecksumAlgorithm(), d.getChecksumValue()), d.getDestinationLocation()))
                .toList();
        return new PackageExportRecord(entity.getId(), TenantId.of(entity.getTenantId()), PackageId.of(entity.getPackageId()),
                entity.getCycleNumber(), entity.getDestinationType(), entity.getExportedAt(),
                CorrelationId.of(entity.getCorrelationId()), documents);
    }
}
