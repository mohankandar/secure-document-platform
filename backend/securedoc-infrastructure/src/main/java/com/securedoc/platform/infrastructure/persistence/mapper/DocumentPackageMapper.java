package com.securedoc.platform.infrastructure.persistence.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.BusinessRecordId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.entity.DocumentPackageEntity;

import java.util.List;
import java.util.Map;

public final class DocumentPackageMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private DocumentPackageMapper() {
    }

    public static DocumentPackage toDomain(DocumentPackageEntity entity, List<Document> documents) {
        PackageMetadata metadata = parseMetadata(entity.getMetadataJson());

        return DocumentPackage.reconstitute(
                PackageId.of(entity.getId()),
                TenantId.of(entity.getTenantId()),
                PackageType.valueOf(entity.getPackageType()),
                entity.getBusinessRecordId() == null ? null : BusinessRecordId.of(entity.getBusinessRecordId()),
                entity.getIntakeFingerprint(),
                entity.getSourceReference(),
                ActorId.of(entity.getCreatedBy()),
                entity.getCreatedAt(),
                PackageStatus.valueOf(entity.getStatus()),
                metadata,
                documents,
                entity.getUpdatedAt()
        );
    }

    public static DocumentPackageEntity toNewEntity(DocumentPackage pkg) {
        DocumentPackageEntity entity = new DocumentPackageEntity();
        entity.setId(pkg.id().value());
        entity.setTenantId(pkg.tenantId().value());
        entity.setPackageType(pkg.packageType().name());
        entity.setBusinessRecordId(pkg.businessRecordId().map(BusinessRecordId::value).orElse(null));
        entity.setIntakeFingerprint(pkg.intakeFingerprint().orElse(null));
        entity.setSourceReference(pkg.sourceReference().orElse(null));
        entity.setStatus(pkg.status().name());
        entity.setMetadataJson(serializeMetadata(pkg.metadata()));
        entity.setCreatedBy(pkg.submittedBy().value());
        entity.setCreatedAt(pkg.createdAt());
        entity.setUpdatedAt(pkg.updatedAt());
        return entity;
    }

    /**
     * Updates mutable fields on an existing managed entity from domain state.
     * Preserves the JPA-managed version field for optimistic locking.
     */
    public static void updateEntity(DocumentPackage pkg, DocumentPackageEntity entity) {
        entity.setStatus(pkg.status().name());
        entity.setBusinessRecordId(pkg.businessRecordId().map(BusinessRecordId::value).orElse(null));
        entity.setIntakeFingerprint(pkg.intakeFingerprint().orElse(null));
        entity.setSourceReference(pkg.sourceReference().orElse(null));
        entity.setMetadataJson(serializeMetadata(pkg.metadata()));
        entity.setUpdatedAt(pkg.updatedAt());
    }

    private static PackageMetadata parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return PackageMetadata.empty();
        }
        try {
            Map<String, String> map = OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
            return PackageMetadata.of(map);
        } catch (JsonProcessingException e) {
            return PackageMetadata.empty();
        }
    }

    private static String serializeMetadata(PackageMetadata metadata) {
        if (metadata.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata.asMap());
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
