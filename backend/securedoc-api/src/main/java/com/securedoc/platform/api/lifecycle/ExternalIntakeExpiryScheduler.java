package com.securedoc.platform.api.lifecycle;

import com.securedoc.platform.application.intake.external.ExternalIntakeMaintenanceService;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** In-process V1 maintenance for incomplete external machine intakes. */
@Component
@ConditionalOnProperty(name = "securedoc.intake.external.expiry-scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class ExternalIntakeExpiryScheduler {
    private static final Logger log = LoggerFactory.getLogger(ExternalIntakeExpiryScheduler.class);
    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));

    private final ExternalIntakeMaintenanceService maintenanceService;

    public ExternalIntakeExpiryScheduler(ExternalIntakeMaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    @Scheduled(fixedDelayString = "${securedoc.intake.external.expiry-scan-interval-ms:300000}")
    public void expireIncompleteIntakes() {
        try {
            var result = maintenanceService.maintainDue(100, SYSTEM_ACTOR, CorrelationId.generate());
            if (result.expiredIntakes() > 0 || result.cleanedIntakes() > 0) {
                log.info("External intake maintenance completed: expired={}, cleaned={}",
                        result.expiredIntakes(), result.cleanedIntakes());
            }
        } catch (RuntimeException e) {
            log.warn("External intake maintenance cycle failed; pending work will be retried");
        }
    }
}
