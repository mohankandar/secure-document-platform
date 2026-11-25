package com.securedoc.platform.api.processing;

import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.processing.service.StagedProcessingService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Task 0048: the Text, Find and Redact workers of this server, started with
 * {@code securedoc.processing.pipeline-mode=staged}. Task 0049: with inline they start only when staged jobs are still
 * open (the setting was switched back), and finish those; new cases go inline. Task 0052: whatever export-mode says, a
 * staged case ends in export jobs, which the export workers finish. Each worker claims the next due job of its stage
 * (claim lock, {@code SKIP LOCKED}, queue order), renews its claim every third of the lease while it works, and saves
 * its result only under its current claim number. One sweep puts jobs whose claim ran out back in the queue. Task 0067:
 * the number of workers per stage follows its queue ({@link ProcessingCapacity}); without it (tests) it is fixed.
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
    private final ProcessingCapacity capacity;
    private final List<ElasticWorkers> workers = new ArrayList<>();
    private ScheduledExecutorService timers;
    /** Task 0060: nothing is claimed while no work may be taken (after a restore, before the erase replay). */
    private WorkGate workGate = WorkGate.open();

    @Autowired
    void workGate(WorkGate gate) {
        this.workGate = gate;
    }

    public StageJobWorkers(StagedProcessingService service, String nodeId) {
        this(service, nodeId, null);
    }

    @Autowired
    public StageJobWorkers(StagedProcessingService service,
                           @Value("${securedoc.logging.node-id:unknown}") String nodeId,
                           ProcessingCapacity capacity) {
        this.capacity = capacity;
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
        if (!workGate.mayTakeWork()) return;
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
        running = true;
        String instance = UUID.randomUUID().toString();
        submit(PipelineStage.TEXT, settings.textWorkers(), instance);
        submit(PipelineStage.FIND, settings.findWorkers(), instance);
        submit(PipelineStage.VISION, settings.visionWorkers(), instance);
        submit(PipelineStage.REDACT, settings.redactWorkers(), instance);
        long every = settings.pollInterval().toMillis();
        timers.scheduleWithFixedDelay(this::sweep, every, every, TimeUnit.MILLISECONDS);
        timers.scheduleWithFixedDelay(this::missedGates, every * OPEN_JOBS_CHECK_POLLS,
                every * OPEN_JOBS_CHECK_POLLS, TimeUnit.MILLISECONDS);
        log.info("STAGE_JOB_WORKERS_STARTED claimLeaseMs={} nodeId={}", settings.claimLease().toMillis(), nodeId);
    }

    private void submit(PipelineStage stage, int count, String instance) {
        String name = stage.name().toLowerCase(java.util.Locale.ROOT);
        ElasticWorkers pool = new ElasticWorkers("stage-" + name, nodeId + "/" + name, instance,
                service.settings().pollInterval().toMillis(), (owner, started) -> turn(stage, owner, started));
        workers.add(pool);
        if (capacity == null) pool.set(count);
        else capacity.drive(stage, pool);
    }

    @PreDestroy
    public void stop() {
        // The timers first, so an open check is interrupted; then its lock is waited for (or it saw "stopped").
        if (timers != null) timers.shutdownNow();
        synchronized (this) {
            stopped = true;
            running = false;
            workers.forEach(ElasticWorkers::stop);
        }
    }

    private boolean turn(PipelineStage stage, String owner, Runnable started) {
        if (!workGate.mayTakeWork()) return false;
        java.util.Set<String> bulk = capacity == null ? java.util.Set.of() : capacity.bulkTurn(stage);
        try {
            Optional<PipelineJob> job = capacity == null ? service.claimNext(stage, owner) : service.claimNext(stage, owner, bulk);
            if (job.isEmpty()) return false;
            if (job.get().priority() != PipelinePriority.BULK) {
                // Not a Bulk job: the place kept for one is free again before the job runs.
                if (capacity != null) capacity.bulkDone(stage, bulk);
                bulk = java.util.Set.of();
            }
            boolean bulkJob = capacity != null && job.get().priority() == PipelinePriority.BULK;
            if (bulkJob) capacity.bulkStarted(stage);
            started.run();
            try {
                runWithRenewal(job.get());
            } finally {
                if (bulkJob) capacity.bulkEnded(stage);
            }
            return true;
        } catch (RuntimeException failure) {
            // The job stays RUNNING until its claim runs out, then goes back to the queue (one attempt).
            log.warn("STAGE_WORKER_FAILED owner={} stage={} type={} code={}", owner, stage,
                    failure.getClass().getSimpleName(), LvErrorCodes.forPlatformFailure(failure).code());
            return false;
        } finally {
            if (capacity != null) capacity.bulkDone(stage, bulk);
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
        if (!workGate.mayTakeWork()) return;
        try {
            service.releaseExpiredClaims();
        } catch (RuntimeException failure) {
            log.warn("STAGE_JOB_CLAIM_SWEEP_FAILED type={} code={}", failure.getClass().getSimpleName(),
                    LvErrorCodes.forPlatformFailure(failure).code());
        }
    }

    private void missedGates() {
        if (!workGate.mayTakeWork()) return;
        try {
            service.runMissedGates();
        } catch (RuntimeException failure) {
            log.warn("STAGE_JOB_MISSED_GATE_SWEEP_FAILED type={} code={}", failure.getClass().getSimpleName(),
                    LvErrorCodes.forPlatformFailure(failure).code());
        }
    }
}
