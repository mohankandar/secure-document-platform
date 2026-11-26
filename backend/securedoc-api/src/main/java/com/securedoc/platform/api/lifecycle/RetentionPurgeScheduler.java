package com.securedoc.platform.api.lifecycle;

import com.securedoc.platform.application.lifecycle.command.PurgeDueRetentionCommand;
import com.securedoc.platform.application.lifecycle.service.DeliveryLeftoverService;
import com.securedoc.platform.application.lifecycle.service.UnrecordedFileSweepService;
import com.securedoc.platform.application.pipeline.FinishedJobCleanupService;
import com.securedoc.platform.application.port.in.PurgeDueRetentionUseCase;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Minimal in-process V1 retention maintenance. Durable scheduling can replace this later. Task 0034: after the purge,
 * the same run removes finished jobs past their keep time, and (task 0049) stored files without a record, and (task 0097) what a
 * killed server left of a delivery to a folder (a failure of one never stops the other).
 */
@Component
@ConditionalOnProperty(name = "securedoc.lifecycle.purge-scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class RetentionPurgeScheduler {
    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeScheduler.class);
    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));

    private final PurgeDueRetentionUseCase purgeUseCase;
    private final FinishedJobCleanupService jobCleanup;
    private final UnrecordedFileSweepService fileSweep;
    private final DeliveryLeftoverService deliveryLeftovers;

    public RetentionPurgeScheduler(PurgeDueRetentionUseCase purgeUseCase, FinishedJobCleanupService jobCleanup,
                                   UnrecordedFileSweepService fileSweep, DeliveryLeftoverService deliveryLeftovers) {
        this.deliveryLeftovers = deliveryLeftovers;
        this.fileSweep = fileSweep;
        this.purgeUseCase = purgeUseCase;
        this.jobCleanup = jobCleanup;
    }

    @Scheduled(fixedDelayString = "${securedoc.lifecycle.purge-interval-ms:900000}")
    public void purgeDueArtifacts() {
        Instant now = Instant.now();
        CorrelationId correlationId = CorrelationId.generate();
        try {
            var result = purgeUseCase.execute(new PurgeDueRetentionCommand(now, 100, SYSTEM_ACTOR, correlationId));
            if (result.redactedCachesPurged() > 0 || result.packagesFullyPurged() > 0) {
                log.info("Retention purge completed: redactedCaches={}, packages={}",
                        result.redactedCachesPurged(), result.packagesFullyPurged());
            }
        } catch (RuntimeException e) {
            log.warn("Retention purge cycle failed; see audit records for item-level sanitized failures");
        }
        try {
            var cleaned = jobCleanup.run(now, SYSTEM_ACTOR, correlationId);
            if (cleaned.jobsRemoved() > 0 || cleaned.alertsRemoved() > 0) {
                log.info("Finished job clean-up completed: jobs={}, alerts={}", cleaned.jobsRemoved(),
                        cleaned.alertsRemoved());
            }
        } catch (RuntimeException e) {
            log.warn("PIPELINE_JOB_CLEANUP_FAILED type={}", e.getClass().getSimpleName());
        }
        try {
            int files = fileSweep.run(now, SYSTEM_ACTOR, correlationId);
            if (files > 0) log.info("UNRECORDED_FILES_CLEANED files={}", files);
        } catch (RuntimeException e) {
            log.warn("UNRECORDED_FILE_SWEEP_FAILED type={}", e.getClass().getSimpleName());
        }
        try {
            int deliveries = deliveryLeftovers.run(SYSTEM_ACTOR, correlationId);
            if (deliveries > 0) log.info("DELIVERY_LEFTOVERS_CLEANED deliveries={}", deliveries);
        } catch (RuntimeException e) {
            log.warn("DELIVERY_LEFTOVER_CLEANUP_FAILED type={}", e.getClass().getSimpleName());
        }
    }
}
