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

    /**
     * Task 0023 (D5, #160): saves a review decision only while the stored finding is still DETECTED.
     * Returns false when someone else decided it first, including two saves that meet in the database.
     * The default (for in-memory test repositories, which have no concurrent writers) just saves.
     */
    default boolean saveDecisionIfPending(Finding decided) {
        save(decided);
        return true;
    }

    Optional<Finding> findByIdAndTenantId(FindingId id, TenantId tenantId);

    List<Finding> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId);

    void deleteByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId);
}
