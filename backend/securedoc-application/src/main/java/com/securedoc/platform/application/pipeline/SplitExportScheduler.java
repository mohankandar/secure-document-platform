package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.UUID;

/** Where derived routing hands a split document to export (task 0027). Task 0121: always a SPLIT_EXPORT job. */
public interface SplitExportScheduler {

    /** Queues one SPLIT_EXPORT job for the derived delivery. Queuing it twice adds nothing. */
    void queueSplitExport(TenantId tenantId, PackageId packageId, DocumentId sourceDocumentId, UUID derivedDeliveryId,
                          IntegrationConnectionId destinationId, int exportCycle, CorrelationId correlationId);
}
