package com.securedoc.platform.application.resilience;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0023 C7 (#134): an AI failover is logged with the primary's code, even when it succeeds. */
class ResilientSensitiveContentAnalysisPortTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void capture() {
        logger = (Logger) LoggerFactory.getLogger(ResilientSensitiveContentAnalysisPort.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logs);
    }

    @Test
    void successfulFailoverStillLogsThatThePrimaryFailed() {
        SensitiveContentAnalysisPort.AnalysisOutput ok = new SensitiveContentAnalysisPort.AnalysisOutput(List.of());
        var port = new ResilientSensitiveContentAnalysisPort(
                request -> { throw failure(TechnicalFailureCode.AI_REJECTED); },
                request -> ok,
                new SelectiveRetryExecutor(1, java.time.Duration.ZERO));

        assertSame(ok, port.analyze(request()));

        List<String> lines = lines();
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("AI_PRIMARY_FAILED_FAILOVER")
                && l.contains("reasonCode=AI_REJECTED")), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("AI_SECONDARY_SUCCEEDED") && l.contains("primaryReasonCode=AI_REJECTED")));
        assertFalse(String.join("\n", lines).contains("provider said"), "No provider text in logs");
    }

    @Test
    void whenBothFailThePrimaryCodeIsKeptInTheLog() {
        var port = new ResilientSensitiveContentAnalysisPort(
                request -> { throw failure(TechnicalFailureCode.AI_TIMEOUT); },
                request -> { throw failure(TechnicalFailureCode.AI_UNAVAILABLE); },
                new SelectiveRetryExecutor(1, java.time.Duration.ZERO));

        TechnicalDependencyException thrown = assertThrows(TechnicalDependencyException.class,
                () -> port.analyze(request()));

        assertEquals(TechnicalFailureCode.AI_UNAVAILABLE, thrown.failureCode());
        assertTrue(lines().stream().anyMatch(l -> l.startsWith("AI_SECONDARY_FAILED")
                && l.contains("reasonCode=AI_UNAVAILABLE") && l.contains("primaryReasonCode=AI_TIMEOUT")));
    }

    private List<String> lines() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static TechnicalDependencyException failure(TechnicalFailureCode code) {
        return new TechnicalDependencyException(TechnicalDependency.AI, code, false, "provider said something");
    }

    private static SensitiveContentAnalysisPort.AnalysisRequest request() {
        return new SensitiveContentAnalysisPort.AnalysisRequest(TenantId.generate(), List.of(),
                SensitiveContentAnalysisPort.AnalysisPolicy.v1Defaults());
    }
}
