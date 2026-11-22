package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.List;

/**
 * Task 0034: removes finished pipeline jobs and cleared reachability alerts once they are older than the cut-off.
 * Each call is its own short statement, so a large backlog never holds the table for long.
 */
public interface FinishedJobCleanupPort {

    /** The tenants to clean, one at a time. */
    List<TenantId> tenantIds();

    /**
     * Removes at most {@code limit} of the tenant's jobs that are DONE or STOPPED and last changed before
     * {@code cutoff}. A job is kept (whatever its age) while its case is still inside its retention: the case's final
     * retention purge has not run, or a redacted or split copy of the case is still stored, or the case has an open
     * job. A DONE job is also kept while its case is still on the Cases list (it shows the case as "Exported").
     *
     * @return how many jobs were removed
     */
    int deleteFinishedJobs(TenantId tenantId, Instant cutoff, int limit);

    /** Removes the tenant's reachability alert if it is CLEARED and was cleared before {@code cutoff}. */
    int deleteClearedAlerts(TenantId tenantId, Instant cutoff);

    /** Nothing to clean (tests and set-ups without the job tables). */
    static FinishedJobCleanupPort none() {
        return new FinishedJobCleanupPort() {
            @Override public List<TenantId> tenantIds() { return List.of(); }
            @Override public int deleteFinishedJobs(TenantId tenantId, Instant cutoff, int limit) { return 0; }
            @Override public int deleteClearedAlerts(TenantId tenantId, Instant cutoff) { return 0; }
        };
    }
}
