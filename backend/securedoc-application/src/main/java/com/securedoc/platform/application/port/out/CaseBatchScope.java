package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0104, step 3 (design chat confirmed 2026-11-27 19:00): which documents the case's current batch delivers, and how
 * many export cycles delivered its earlier batches. A case of one batch behaves exactly as before: every document, and
 * the export-cycle limit counted from the first cycle.
 */
public interface CaseBatchScope {

    /** Cycles that delivered the batches before the current one; 0 for a case of one batch. */
    int cyclesBeforeCurrentBatch(TenantId tenantId, PackageId packageId);

    /**
     * The documents of the current batch, when the case has more than one batch. Empty for a case of one batch: then
     * every document of the case is delivered, as before.
     */
    Optional<Set<UUID>> currentBatchDocuments(TenantId tenantId, PackageId packageId);

    /** The current batch was delivered in this export cycle (a re-export of it moves the cycle on). */
    void currentBatchDelivered(TenantId tenantId, PackageId packageId, int exportCycle);

    /** No batches: every case is a case of one batch. */
    static CaseBatchScope singleBatch() {
        return new CaseBatchScope() {
            @Override public int cyclesBeforeCurrentBatch(TenantId tenantId, PackageId packageId) { return 0; }
            @Override public Optional<Set<UUID>> currentBatchDocuments(TenantId tenantId, PackageId packageId) {
                return Optional.empty();
            }
            @Override public void currentBatchDelivered(TenantId tenantId, PackageId packageId, int exportCycle) { }
        };
    }
}
