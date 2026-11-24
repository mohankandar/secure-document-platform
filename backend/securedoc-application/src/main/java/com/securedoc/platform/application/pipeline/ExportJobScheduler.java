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

    /**
     * Task 0052: true when the case must not be exported inline, because an export job has it: one of its EXPORT jobs
     * is still open (the workers finish those after a switch back to inline), or this run's job is done. A stopped job
     * sends nothing any more and does not count.
     */
    default boolean jobHasCase(TenantId tenantId, PackageId packageId, int exportCycle) {
        return false;
    }

    /**
     * Queues one EXPORT job per destination for this case run. Queuing the same run twice adds nothing. With export
     * jobs off (task 0052) only a case of the staged pipeline is queued (it has stage jobs); any other is refused.
     */
    void queueCaseExport(TenantId tenantId, PackageId packageId, int exportCycle, CorrelationId correlationId);
}
