package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

/** Where processing hands a finished case to export (task 0027). Task 0121: always an export job. */
public interface ExportJobScheduler {

    /** Queues one EXPORT job per destination for this case run. Queuing the same run twice adds nothing. */
    void queueCaseExport(TenantId tenantId, PackageId packageId, int exportCycle, CorrelationId correlationId);
}
