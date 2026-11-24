package com.securedoc.platform.api.processing;

import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.processing.service.StagedProcessingService;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineStage;
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
 * Task 0048: the Text, Find and Redact workers of this server, started with
 * {@code securedoc.processing.pipeline-mode=staged}. Task 0049: with inline they start only when staged jobs are still
 * open (the setting was switched back), and finish those; new cases go inline. Task 0052: whatever export-mode says, a
 * staged case ends in export jobs, which the export workers finish. Each worker claims the next due job of its stage
 * (claim lock, {@code SKIP LOCKED}, queue order), renews its claim every third of the lease while it works, and saves
 * its result only under its current claim number. One sweep puts jobs whose claim ran out back in the queue. Fixed
 * numbers per stage for now; workers that grow with the queue are P3.
 */
@Component
public class StageJobWorkers {
    private static final Logger log = LoggerFactory.getLogger(StageJobWorkers.class);

    private final StagedProcessingService service;
    private final String nodeId;
    /** In inline mode, open staged jobs are looked for every this many poll intervals. */
    static final int OPEN_JOBS_CHECK_POLLS = 10;
    private volatile boolean running;
    /** Task 0064: a check for open jobs that was under way when the server stopped starts no worker any more. */
    private boolean stopped;
    private ExecutorService workers;
    private ScheduledExecutorService timers;

    public StageJobWorkers(StagedProcessingService service,
                           @Value("${securedoc.logging.node-id:unknown}") String nodeId) {
        this.service = service;
        this.nodeId = nodeId == null || nodeId.isBlank() ? "unknown" : nodeId.trim();
    }

    @PostConstruct
    public void start() {
        timers = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "stage-worker-timer");
            thread.setDaemon(true);
            return thread;
        });
        if (!service.settings().staged()) {
            log.info("STAGE_JOB_WORKERS_OFF pipelineMode=inline");
            long every = service.settings().pollInterval().toMillis() * OPEN_JOBS_CHECK_POLLS;
            timers.scheduleWithFixedDelay(this::finishOpenJobs, 0, every, TimeUnit.MILLISECONDS);
            return;
        }
        startWorkers();
    }

    /** In inline mode: the open staged jobs, if any (also ones a server still staged queued later), are finished. */
    private synchronized void finishOpenJobs() {
        try {
            if (running) return;
            service.runMissedGates();
            if (!service.hasOpenJobs() || stopped) return;
            log.info("STAGE_JOB_WORKERS_FINISHING pipelineMode=inline reasonCode=OPEN_STAGE_JOBS");
            startWorkers();
        } catch (RuntimeException failure) {
            log.warn("STAGE_JOB_OPEN_CHECK_FAILED type={} code={}", failure.getClass().getSimpleName(),
                    LvErrorCodes.forPlatformFailure(failure).code());
        }
    }

    private void startWorkers() {
        var settings = service.settings();
        int total = settings.textWorkers() + settings.findWorkers() + settings.visionWorkers()
                + settings.redactWorkers();
        AtomicInteger number = new AtomicInteger();
        running = true;
        workers = Executors.newFixedThreadPool(total, runnable -> {
            Thread thread = new Thread(runnable, "stage-worker-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        String instance = UUID.randomUUID().toString();
        submit(PipelineStage.TEXT, settings.textWorkers(), instance);
        submit(PipelineStage.FIND, settings.findWorkers(), instance);
        submit(PipelineStage.VISION, settings.visionWorkers(), instance);
        submit(PipelineStage.REDACT, settings.redactWorkers(), instance);
        long every = settings.pollInterval().toMillis();
        timers.scheduleWithFixedDelay(this::sweep, every, every, TimeUnit.MILLISECONDS);
        timers.scheduleWithFixedDelay(this::missedGates, every * OPEN_JOBS_CHECK_POLLS,
                every * OPEN_JOBS_CHECK_POLLS, TimeUnit.MILLISECONDS);
        log.info("STAGE_JOB_WORKERS_STARTED text={} find={} vision={} redact={} claimLeaseMs={} nodeId={}",
                settings.textWorkers(), settings.findWorkers(), settings.visionWorkers(), settings.redactWorkers(),
                settings.claimLease().toMillis(), nodeId);
    }

    private void submit(PipelineStage stage, int count, String instance) {
        for (int i = 1; i <= count; i++) {
            String owner = nodeId + "/" + stage.name().toLowerCase(java.util.Locale.ROOT) + "-worker-" + i + "/"
                    + instance;
            workers.submit(() -> loop(stage, owner));
        }
    }

    @PreDestroy
    public void stop() {
        // The timers first, so an open check is interrupted; then its lock is waited for (or it saw "stopped").
        if (timers != null) timers.shutdownNow();
        synchronized (this) {
            stopped = true;
            running = false;
            if (workers != null) workers.shutdownNow();
        }
    }

    private void loop(PipelineStage stage, String owner) {
        long idle = service.settings().pollInterval().toMillis();
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                Optional<PipelineJob> job = service.claimNext(stage, owner);
                if (job.isPresent()) {
                    runWithRenewal(job.get());
                } else {
                    Thread.sleep(idle);
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                // The job stays RUNNING until its claim runs out, then goes back to the queue (one attempt).
                log.warn("STAGE_WORKER_FAILED owner={} stage={} type={} code={}", owner, stage,
                        failure.getClass().getSimpleName(), LvErrorCodes.forPlatformFailure(failure).code());
                sleepQuietly(idle);
            }
        }
    }

    private void runWithRenewal(PipelineJob job) {
        long every = Math.max(1000L, service.settings().claimLease().toMillis() / 3);
        ScheduledFuture<?> renewal = timers.scheduleAtFixedRate(() -> {
            try {
                if (!service.renew(job)) {
                    log.warn("PIPELINE_JOB_CLAIM_NOT_RENEWED job={} stage={} claimToken={}", job.id(), job.stage(),
                            job.claimToken());
                }
            } catch (RuntimeException renewFailure) {
                log.warn("PIPELINE_JOB_CLAIM_RENEW_FAILED job={} type={}", job.id(),
                        renewFailure.getClass().getSimpleName());
            }
        }, every, every, TimeUnit.MILLISECONDS);
        try {
            service.run(job);
        } finally {
            renewal.cancel(false);
        }
    }

    private void sweep() {
        try {
            service.releaseExpiredClaims();
        } catch (RuntimeException failure) {
            log.warn("STAGE_JOB_CLAIM_SWEEP_FAILED type={} code={}", failure.getClass().getSimpleName(),
                    LvErrorCodes.forPlatformFailure(failure).code());
        }
    }

    private void missedGates() {
        try {
            service.runMissedGates();
        } catch (RuntimeException failure) {
            log.warn("STAGE_JOB_MISSED_GATE_SWEEP_FAILED type={} code={}", failure.getClass().getSimpleName(),
                    LvErrorCodes.forPlatformFailure(failure).code());
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }
}
