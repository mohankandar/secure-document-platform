package com.securedoc.platform.api.processing;

import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.pipeline.ExportJobService;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Task 0027: a small fixed pool of export workers, started only with {@code securedoc.processing.export-mode=job}.
 * Each worker claims the next due export job, renews its claim while it runs, and saves the result (only if its claim
 * is still current). One maintenance task puts jobs whose claim ran out back in the queue and probes paused
 * destinations. Worker scaling is P3.
 */
@Component
public class ExportJobWorkers {
    private static final Logger log = LoggerFactory.getLogger(ExportJobWorkers.class);

    private final ExportJobService service;
    private final long pollIntervalMs;
    private final String nodeId;
    private volatile boolean running;
    private ExecutorService workers;
    private ScheduledExecutorService timers;

    public ExportJobWorkers(ExportJobService service,
                            @Value("${securedoc.processing.export-jobs.poll-interval-ms:2000}") long pollIntervalMs,
                            @Value("${securedoc.logging.node-id:unknown}") String nodeId) {
        this.service = service;
        this.pollIntervalMs = Math.max(100L, pollIntervalMs);
        this.nodeId = nodeId == null || nodeId.isBlank() ? "unknown" : nodeId.trim();
    }

    @PostConstruct
    public void start() {
        if (!service.settings().jobMode()) {
            log.info("EXPORT_JOB_WORKERS_OFF exportMode=inline");
            return;
        }
        int total = service.settings().workersTotal();
        AtomicInteger number = new AtomicInteger();
        running = true;
        workers = Executors.newFixedThreadPool(total, runnable -> {
            Thread thread = new Thread(runnable, "export-worker-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        timers = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "export-worker-timer");
            thread.setDaemon(true);
            return thread;
        });
        String instance = UUID.randomUUID().toString();
        for (int i = 1; i <= total; i++) {
            String owner = nodeId + "/export-worker-" + i + "/" + instance;
            workers.submit(() -> loop(owner));
        }
        timers.scheduleWithFixedDelay(this::maintenance, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
        log.info("EXPORT_JOB_WORKERS_STARTED workers={} perDestination={} nodeId={}", total,
                service.settings().workersPerDestination(), nodeId);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (workers != null) workers.shutdownNow();
        if (timers != null) timers.shutdownNow();
    }

    private void loop(String owner) {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                Optional<PipelineJob> job = service.claimNext(owner);
                if (job.isPresent()) {
                    runWithRenewal(job.get());
                } else {
                    Thread.sleep(pollIntervalMs);
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                // The job stays RUNNING until its claim runs out, then goes back to the queue (one attempt).
                log.warn("EXPORT_WORKER_FAILED owner={} type={} code={}", owner, failure.getClass().getSimpleName(),
                        LvErrorCodes.forPlatformFailure(failure).code());
                sleepQuietly();
            }
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

    private void sleepQuietly() {
        try {
            Thread.sleep(pollIntervalMs);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }
}
