package com.securedoc.platform.infrastructure.persistence.mapper;

import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import com.securedoc.platform.infrastructure.persistence.entity.DocumentEntity;

import java.util.List;

public final class DocumentMapper {

    private DocumentMapper() {
    }

    public static Document toDomain(DocumentEntity entity) {
        return Document.reconstitute(
                DocumentId.of(entity.getId()),
                TenantId.of(entity.getTenantId()),
                PackageId.of(entity.getPackageId()),
                FileName.of(entity.getFileName()),
                MimeType.of(entity.getMimeType()),
                entity.getSizeBytes(),
                Checksum.of(entity.getChecksumAlgorithm(), entity.getChecksumValue()),
                DocumentType.valueOf(entity.getDocumentType()),
                entity.getCreatedAt(),
                DocumentStatus.valueOf(entity.getStatus()),
                List.of(), // pages loaded separately if needed
                entity.getUpdatedAt()
        );
    }

    public static DocumentEntity toNewEntity(Document doc) {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(doc.id().value());
        entity.setTenantId(doc.tenantId().value());
        entity.setPackageId(doc.packageId().value());
        entity.setDocumentType(doc.documentType().name());
        entity.setStatus(doc.status().name());
        entity.setFileName(doc.fileName().value());
        entity.setMimeType(doc.mimeType().value());
        entity.setSizeBytes(doc.sizeBytes());
        entity.setChecksumAlgorithm(doc.checksum().algorithm());
        entity.setChecksumValue(doc.checksum().value());
        entity.setPageCount(doc.pageCount());
        entity.setCreatedAt(doc.uploadedAt());
        entity.setUpdatedAt(doc.updatedAt());
        return entity;
    }

    /**
     * Updates mutable fields on an existing managed entity from domain state.
     * Preserves the JPA-managed version field for optimistic locking.
     */
    public static void updateEntity(Document doc, DocumentEntity entity) {
        entity.setStatus(doc.status().name());
        entity.setPageCount(doc.pageCount());
        entity.setUpdatedAt(doc.updatedAt());
    }
}
