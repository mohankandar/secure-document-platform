package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.BulkWindow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Task 0079: the sources marked Bulk, and how far their cases are. */
public interface BulkSourceRepository {

    /** One source; its key as in case_priorities. */
    record BulkSource(TenantId tenantId, String sourceKey, BulkWindow window, Instant markedAt) {
        /** What the queue is asked with. */
        public String reference() {
            return reference(tenantId, sourceKey);
        }

        public static String reference(TenantId tenantId, String sourceKey) {
            return tenantId.value() + "/" + sourceKey;
        }
    }

    /** Cases of the source taken in as Bulk: exported, stopped (failed, rejected), and all the others. */
    record Counts(long done, long failed, long waiting) { }

    /** Every entity's. */
    List<BulkSource> all();

    Optional<BulkSource> find(TenantId tenantId, String sourceKey);

    /** The time it was first marked stays. */
    void save(TenantId tenantId, String sourceKey, BulkWindow window);

    /** Its cases still waiting as Bulk become Low (they would wait for a window that is gone). */
    void remove(TenantId tenantId, String sourceKey);

    Counts counts(TenantId tenantId, String sourceKey);

    /** Cases exported since then. */
    long doneSince(TenantId tenantId, String sourceKey, Instant since);
}
