package com.securedoc.platform.api.processing;

import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.pipeline.ExportJobService;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelinePriority;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import com.securedoc.platform.application.port.out.WorkGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Task 0027: the export workers. Task 0121: export is always a job, so they always start. Each worker claims the next due export job, renews its claim while it runs, and saves the result (only if its claim
 * is still current). One maintenance task puts jobs whose claim ran out back in the queue and probes paused
 * destinations. Task 0067: the number of workers follows the queue ({@link ProcessingCapacity}); without it (tests) it
 * is fixed.
 */
@Component
public class ExportJobWorkers {
    private static final Logger log = LoggerFactory.getLogger(ExportJobWorkers.class);

    private final ExportJobService service;
    private final long pollIntervalMs;
    private final String nodeId;
    private volatile boolean running;
    private volatile boolean stopped;
    private final ProcessingCapacity capacity;
    private ElasticWorkers workers;
    private ScheduledExecutorService timers;
    /** Task 0060: nothing is claimed while no work may be taken (after a restore, before the erase replay). */
    private WorkGate workGate = WorkGate.open();

    @Autowired
    void workGate(WorkGate gate) {
        this.workGate = gate;
    }

    public ExportJobWorkers(ExportJobService service, long pollIntervalMs, String nodeId) {
        this(service, pollIntervalMs, nodeId, null);
    }

    @Autowired
    public ExportJobWorkers(ExportJobService service,
                            @Value("${securedoc.processing.export-jobs.poll-interval-ms:2000}") long pollIntervalMs,
                            @Value("${securedoc.logging.node-id:unknown}") String nodeId,
                            ProcessingCapacity capacity) {
        this.capacity = capacity;
        this.service = service;
        this.pollIntervalMs = Math.max(100L, pollIntervalMs);
        this.nodeId = nodeId == null || nodeId.isBlank() ? "unknown" : nodeId.trim();
    }

    @PostConstruct
    public void start() {
        timers = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "export-worker-timer");
            thread.setDaemon(true);
            return thread;
        });
        startWorkers();
    }

    private void startWorkers() {
        running = true;
        workers = new ElasticWorkers("export-worker", nodeId + "/export", UUID.randomUUID().toString(), pollIntervalMs,
                this::turn);
        if (capacity == null) workers.set(service.settings().workersTotal());
        else capacity.drive(PipelineStage.EXPORT, workers);
        timers.scheduleWithFixedDelay(this::maintenance, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
        log.info("EXPORT_JOB_WORKERS_STARTED perDestination={} nodeId={}",
                service.settings().workersPerDestination(), nodeId);
    }

    @PreDestroy
    public synchronized void stop() {
        stopped = true;
        running = false;
        if (workers != null) workers.stop();
        if (timers != null) timers.shutdownNow();
    }

    private boolean turn(String owner, Runnable started) {
        if (!workGate.mayTakeWork()) return false;
        java.util.Set<String> bulk = capacity == null ? java.util.Set.of() : capacity.bulkTurn(PipelineStage.EXPORT);
        try {
            Optional<PipelineJob> job = capacity == null ? service.claimNext(owner) : service.claimNext(owner, bulk);
            if (job.isEmpty()) return false;
            if (job.get().priority() != PipelinePriority.BULK) {
                // Not a Bulk job: the place kept for one is free again before the job runs.
                if (capacity != null) capacity.bulkDone(PipelineStage.EXPORT, bulk);
                bulk = java.util.Set.of();
            }
            boolean bulkJob = capacity != null && job.get().priority() == PipelinePriority.BULK;
            if (bulkJob) capacity.bulkStarted(PipelineStage.EXPORT);
            started.run();
            try {
                runWithRenewal(job.get());
            } finally {
                if (bulkJob) capacity.bulkEnded(PipelineStage.EXPORT);
            }
            return true;
        } catch (RuntimeException failure) {
            // The job stays RUNNING until its claim runs out, then goes back to the queue (one attempt).
            log.warn("EXPORT_WORKER_FAILED owner={} type={} code={}", owner, failure.getClass().getSimpleName(),
                    LvErrorCodes.forPlatformFailure(failure).code());
            return false;
        } finally {
            if (capacity != null) capacity.bulkDone(PipelineStage.EXPORT, bulk);
        }
    }

    private void runWithRenewal(PipelineJob job) {
        long every = Math.max(500L, service.settings().claimLease().toMillis() / 3);
        ScheduledFuture<?> renewal = timers.scheduleAtFixedRate(() -> {
            try {
                if (!service.renew(job)) {
                    log.warn("EXPORT_JOB_CLAIM_LOST job={} claimToken={}", job.id(), job.claimToken());
                }
            } catch (RuntimeException renewFailure) {
                log.warn("EXPORT_JOB_CLAIM_RENEW_FAILED job={} type={}", job.id(),
                        renewFailure.getClass().getSimpleName());
            }
        }, every, every, TimeUnit.MILLISECONDS);
        try {
            service.run(job);
        } finally {
            renewal.cancel(false);
        }
    }

    private void maintenance() {
        if (!workGate.mayTakeWork()) return;
        try {
            service.releaseExpiredClaims();
        } catch (RuntimeException failure) {
            log.warn("EXPORT_JOB_CLAIM_SWEEP_FAILED type={} code={}", failure.getClass().getSimpleName(),
                    LvErrorCodes.forPlatformFailure(failure).code());
        }
        try {
            service.probePausedDestinations();
        } catch (RuntimeException failure) {
            log.warn("EXPORT_DESTINATION_PROBE_SWEEP_FAILED type={} code={}", failure.getClass().getSimpleName(),
                    LvErrorCodes.forPlatformFailure(failure).code());
        }
        // Task 0028: "can't reach its destinations" is checked again every probe interval; 429 workers come back.
        try {
            service.checkUnreachableAlerts();
        } catch (RuntimeException failure) {
            log.warn("EXPORT_ALERT_CHECK_SWEEP_FAILED type={} code={}", failure.getClass().getSimpleName(),
                    LvErrorCodes.forPlatformFailure(failure).code());
        }
        try {
            service.restoreWorkers();
        } catch (RuntimeException failure) {
            log.warn("EXPORT_WORKER_RESTORE_SWEEP_FAILED type={} code={}", failure.getClass().getSimpleName(),
                    LvErrorCodes.forPlatformFailure(failure).code());
        }
    }
}
