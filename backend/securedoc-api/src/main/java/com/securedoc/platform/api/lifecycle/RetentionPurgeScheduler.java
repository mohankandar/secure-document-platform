package com.securedoc.platform.api.lifecycle;

import com.securedoc.platform.application.lifecycle.command.PurgeDueRetentionCommand;
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

/** Minimal in-process V1 retention maintenance. Durable scheduling can replace this later. */
@Component
@ConditionalOnProperty(name = "securedoc.lifecycle.purge-scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class RetentionPurgeScheduler {
    private static final Logger log = LoggerFactory.getLogger(RetentionPurgeScheduler.class);
    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));

    private final PurgeDueRetentionUseCase purgeUseCase;

    public RetentionPurgeScheduler(PurgeDueRetentionUseCase purgeUseCase) {
        this.purgeUseCase = purgeUseCase;
    }

    @Scheduled(fixedDelayString = "${securedoc.lifecycle.purge-interval-ms:900000}")
    public void purgeDueArtifacts() {
        try {
            var result = purgeUseCase.execute(new PurgeDueRetentionCommand(
                    Instant.now(), 100, SYSTEM_ACTOR, CorrelationId.generate()));
            if (result.redactedCachesPurged() > 0 || result.packagesFullyPurged() > 0) {
                log.info("Retention purge completed: redactedCaches={}, packages={}",
                        result.redactedCachesPurged(), result.packagesFullyPurged());
            }
        } catch (RuntimeException e) {
            log.warn("Retention purge cycle failed; see audit records for item-level sanitized failures");
        }
    }
}
