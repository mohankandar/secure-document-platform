package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for persisting and querying findings.
 * All query methods are tenant-scoped.
 */
public interface FindingRepository {

    void save(Finding finding);

    void saveAll(List<Finding> findings);

    Optional<Finding> findByIdAndTenantId(FindingId id, TenantId tenantId);

    List<Finding> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId);
}
