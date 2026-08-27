package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for persisting and querying documents.
 */
public interface DocumentRepository {

    void save(Document document);

    Optional<Document> findById(DocumentId id);

    Optional<Document> findByIdAndTenantId(DocumentId id, TenantId tenantId);

    List<Document> findByPackageId(PackageId packageId);

    List<Document> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId);
}
