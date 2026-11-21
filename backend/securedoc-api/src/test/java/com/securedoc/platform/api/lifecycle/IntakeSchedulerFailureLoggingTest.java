package com.securedoc.platform.api.lifecycle;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.securedoc.platform.api.integration.PollingScheduler;
import com.securedoc.platform.application.intake.external.ExternalIntakeMaintenanceService;
import com.securedoc.platform.application.intake.polling.PollingIntakeService;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0023 #333, #334, #335: scheduler failures log the exception type and a code, never the message. */
class IntakeSchedulerFailureLoggingTest {

    private static final String SECRET = "jdbc:postgresql://lexa:hunter2@db/Jane-Doe";

    @Test
    void aPollingRunThatThrowsLogsTypeAndCode() {
        PollingIntakeService service = mock(PollingIntakeService.class);
        var ref = new PollingIntakeService.PollingSourceRef(TenantId.generate(), IntegrationConnectionId.generate());
        when(service.dueSources()).thenReturn(List.of(ref));
        when(service.runScheduled(any(), any())).thenThrow(new IllegalStateException(SECRET));
        List<String> lines = capture(PollingScheduler.class, () -> new PollingScheduler(service, Runnable::run).runDue());
        assertTrue(lines.stream().anyMatch(l -> l.contains("POLLING_SOURCE_RUN_FAILED")
                && l.contains("connectionId=" + ref.connectionId()) && l.contains("exceptionType=IllegalStateException")), lines.toString());
        lines.forEach(l -> assertFalse(l.contains("hunter2") || l.contains("Jane"), l));
    }

    @Test
    void aPollingDiscoveryThatThrowsLogsTypeAndCode() {
        PollingIntakeService service = mock(PollingIntakeService.class);
        when(service.dueSources()).thenThrow(new IllegalStateException(SECRET));
        List<String> lines = capture(PollingScheduler.class, () -> new PollingScheduler(service, Runnable::run).runDue());
        assertTrue(lines.stream().anyMatch(l -> l.contains("POLLING_DISCOVERY_FAILED")
                && l.contains("exceptionType=IllegalStateException")), lines.toString());
        lines.forEach(l -> assertFalse(l.contains("hunter2"), l));
    }

    @Test
    void anIntakeMaintenanceCycleThatThrowsLogsTypeAndCode() {
        ExternalIntakeMaintenanceService maintenance = mock(ExternalIntakeMaintenanceService.class);
        when(maintenance.maintainDue(anyInt(), any(), any())).thenThrow(new IllegalStateException(SECRET));
        List<String> lines = capture(ExternalIntakeExpiryScheduler.class,
                () -> new ExternalIntakeExpiryScheduler(maintenance).expireIncompleteIntakes());
        assertTrue(lines.stream().anyMatch(l -> l.contains("EXTERNAL_INTAKE_MAINTENANCE_FAILED")
                && l.contains("reasonCode=MAINTENANCE_CYCLE_FAILED") && l.contains("exceptionType=IllegalStateException")),
                lines.toString());
        lines.forEach(l -> assertFalse(l.contains("hunter2"), l));
    }

    private static List<String> capture(Class<?> type, Runnable work) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            work.run();
        } finally {
            logger.detachAppender(appender);
        }
        appender.list.forEach(e -> assertNull(e.getThrowableProxy(), "no stack trace"));
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
