package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

/** Where processing hands a finished case to export (task 0027). */
public interface ExportJobScheduler {

    /** Today's behaviour: nothing is queued; processing exports the case itself. */
    ExportJobScheduler INLINE = new ExportJobScheduler() {
        @Override
        public boolean jobMode() {
            return false;
        }

        @Override
        public void queueCaseExport(TenantId tenantId, PackageId packageId, int exportCycle,
                                    CorrelationId correlationId) {
            throw new IllegalStateException("Export jobs are off (securedoc.processing.export-mode=inline)");
        }
    };

    boolean jobMode();

    /** Queues one EXPORT job per destination for this case run. Queuing the same run twice adds nothing. */
    void queueCaseExport(TenantId tenantId, PackageId packageId, int exportCycle, CorrelationId correlationId);
}
