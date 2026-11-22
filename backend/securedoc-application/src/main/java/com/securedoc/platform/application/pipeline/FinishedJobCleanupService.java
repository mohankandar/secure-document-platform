package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.application.port.out.FinishedJobCleanupPort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Task 0034 (staged pipeline P1b): finished jobs are removed automatically after a set time; the audit trail keeps
 * the permanent record. Runs inside the retention purge (the lifecycle maintenance), not on its own schedule.
 *
 * <p>Per tenant: finished jobs are removed in batches until a batch comes back short (at most
 * {@link #MAX_BATCHES_PER_TENANT} batches per run; the rest waits for the next run), then the cleared reachability
 * alert. A tenant with anything removed gets one {@link AuditAction#PIPELINE_JOBS_CLEANED} record with the counts
 * and the cut-off date only. With export-mode inline the tables are empty and nothing happens.
 */
public final class FinishedJobCleanupService {
    private static final Logger log = LoggerFactory.getLogger(FinishedJobCleanupService.class);
    static final int MAX_BATCHES_PER_TENANT = 100;

    private final FinishedJobCleanupPort store;
    private final AuditService audit;
    private final FinishedJobCleanupSettings settings;

    public FinishedJobCleanupService(FinishedJobCleanupPort store, AuditService audit,
                                     FinishedJobCleanupSettings settings) {
        this.store = Objects.requireNonNull(store);
        this.audit = Objects.requireNonNull(audit);
        this.settings = Objects.requireNonNull(settings);
    }

    /** The jobs and alerts removed in one run, over all tenants. */
    public record Result(int jobsRemoved, int alertsRemoved) { }

    public Instant cutoff(Instant now) {
        return now.minus(Duration.ofDays(settings.keepFinishedDays()));
    }

    public Result run(Instant now, ActorId actorId, CorrelationId correlationId) {
        Instant cutoff = cutoff(now);
        int jobsTotal = 0;
        int alertsTotal = 0;
        for (TenantId tenantId : store.tenantIds()) {
            try {
                int jobs = 0;
                for (int batch = 0; batch < MAX_BATCHES_PER_TENANT; batch++) {
                    int removed = store.deleteFinishedJobs(tenantId, cutoff, settings.batchSize());
                    jobs += removed;
                    if (removed < settings.batchSize()) break;
                }
                int alerts = store.deleteClearedAlerts(tenantId, cutoff);
                if (jobs + alerts > 0) {
                    audit.recordSuccess(tenantId, actorId, AuditAction.PIPELINE_JOBS_CLEANED, "Tenant",
                            tenantId.toString(), correlationId, "Finished jobs cleaned: jobs=" + jobs + ", alerts="
                                    + alerts + ", cutoff=" + cutoff.atOffset(ZoneOffset.UTC).toLocalDate());
                }
                jobsTotal += jobs;
                alertsTotal += alerts;
            } catch (RuntimeException e) {
                // Codes only; the next run tries again.
                log.warn("PIPELINE_JOB_CLEANUP_FAILED type={}", e.getClass().getSimpleName());
            }
        }
        return new Result(jobsTotal, alertsTotal);
    }
}
