package com.securedoc.platform.api.lifecycle;

import com.securedoc.platform.application.lifecycle.service.EmptyCaseExpiryService;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Task 0111: closes cases that still have no documents after the set time (securedoc.cases.empty-case-expiry-days). */
@Component
@ConditionalOnProperty(name = "securedoc.cases.empty-case-expiry-enabled", havingValue = "true", matchIfMissing = true)
public class EmptyCaseExpiryScheduler {
    private static final Logger log = LoggerFactory.getLogger(EmptyCaseExpiryScheduler.class);
    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));

    private final EmptyCaseExpiryService service;

    public EmptyCaseExpiryScheduler(EmptyCaseExpiryService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "${securedoc.cases.empty-case-expiry-initial-delay-ms:60000}",
            fixedDelayString = "${securedoc.cases.empty-case-expiry-interval-ms:3600000}")
    public void expireEmptyCases() {
        try {
            int closed = service.expireDue(Instant.now(), 100, SYSTEM_ACTOR, CorrelationId.generate());
            if (closed > 0) log.info("EMPTY_CASES_EXPIRED count={} afterDays={}", closed, service.after().toDays());
        } catch (RuntimeException e) {
            // The type and the LV code, never the message.
            log.warn("EMPTY_CASE_EXPIRY_FAILED exceptionType={} code={}; retried at the next run", e.getClass().getSimpleName(),
                    com.securedoc.platform.application.error.LvErrorCodes.forPlatformFailure(e).code());
        }
    }
}
