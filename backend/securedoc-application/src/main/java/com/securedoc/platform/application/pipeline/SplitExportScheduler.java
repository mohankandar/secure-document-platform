package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.UUID;

/** Where derived routing hands a split document to export, in job mode (task 0027). */
public interface SplitExportScheduler {

    /**
     * Task 0052: true when this case run's split documents are sent at once and not queued: export jobs are off and no
     * export job has the run. The split documents of a case that a job exports are always jobs.
     */
    default boolean splitsGoInline(TenantId tenantId, PackageId packageId, int exportCycle) {
        return false;
    }

    /** Queues one SPLIT_EXPORT job for the derived delivery. Queuing it twice adds nothing. */
    void queueSplitExport(TenantId tenantId, PackageId packageId, DocumentId sourceDocumentId, UUID derivedDeliveryId,
                          IntegrationConnectionId destinationId, int exportCycle, CorrelationId correlationId);
}
