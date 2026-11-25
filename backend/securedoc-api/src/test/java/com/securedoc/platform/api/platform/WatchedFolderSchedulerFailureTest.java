package com.securedoc.platform.api.platform;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.securedoc.platform.application.intake.watched.WatchedFolderIntakeService;
import com.securedoc.platform.application.port.out.WatchedFolderLeasePort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.infrastructure.intake.WatchedFolderProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Task 0023 #57, #296, #340: scheduled watched-folder failures are recorded with a code and a type, never a path. */
class WatchedFolderSchedulerFailureTest {

    private final WatchedFolderIntakeService intake = mock(WatchedFolderIntakeService.class);
    private final WatchedFolderProperties folder = new WatchedFolderProperties();
    private final PlatformAdminProperties admin = new PlatformAdminProperties();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final WatchedFolderScheduler scheduler = new WatchedFolderScheduler(intake, folder, admin,
            new AuditService(audits::add), mock(WatchedFolderLeasePort.class));
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(WatchedFolderScheduler.class);

    @BeforeEach
    void setUp() {
        when(intake.gate()).thenReturn(com.securedoc.platform.application.intake.IntakeGate.OPEN);
        folder.setEnabled(true);
        admin.setWatchedFolderSchedulingEnabled(true);
        admin.setWatchedFolderTenantId(UUID.randomUUID().toString());
        admin.setWatchedFolderActorId(UUID.randomUUID().toString());
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void aFailedScheduledScanIsRecordedWithTypeAndCodeAndNotRethrownWithItsPath() {
        when(intake.scan(any(), any(), any())).thenThrow(new IllegalStateException("Unable to scan watched-folder intake root",
                new IOException("C:\\drops\\Jane Doe - loan 42")));

        assertDoesNotThrow(scheduler::scheduledScan, "Spring's scheduler would print the cause and its path");

        assertEquals("FAILED", scheduler.status().lastScanOutcome());
        AuditRecord failed = audits.stream().filter(r -> r.action() == AuditAction.WATCHED_FOLDER_SCAN_TRIGGERED)
                .findFirst().orElseThrow();
        assertEquals("Watched-folder scan failed: code=LV-INT-034, detail=SCAN_FAILED, mode=SCHEDULED, exceptionType=IllegalStateException",
                failed.detail());
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("reasonCode=SCAN_FAILED exceptionType=IllegalStateException")));
        logs.list.forEach(e -> {
            assertNull(e.getThrowableProxy(), "no stack trace with the cause");
            assertFalse(e.getFormattedMessage().contains("Jane"), e.getFormattedMessage());
        });
    }

    /** Task 0077: the folder of an entity the licence doesn't cover waits; the log line has the LV code. */
    @Test
    void aFolderStoppedByTheLicenceIsLoggedWithItsCode() {
        when(intake.licenceRefusal(any())).thenReturn(java.util.Optional.of(
                new com.securedoc.platform.application.licensing.LicenseEnforcementException("LICENSE_TENANT_MISMATCH", "x",
                        com.securedoc.platform.domain.licensing.LicenseValidationStatus.TENANT_MISMATCH)));

        scheduler.scheduledScan();

        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage()
                .endsWith("reasonCode=LICENSE_TENANT_MISMATCH code=LV-LIC-003")));
        verify(intake, never()).scan(any(), any(), any());
    }

    @Test
    void anInvalidScheduledIdentityIsLoggedOnceWithTheSettingNameNotItsValue() {
        admin.setWatchedFolderTenantId("Jane-Doe-not-a-uuid");

        scheduler.scheduledScan();
        scheduler.scheduledScan();

        assertEquals("NOT_CONFIGURED", scheduler.status().lastScanOutcome());
        List<String> warnings = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("WATCHED_FOLDER_CONFIGURATION_INVALID")).toList();
        assertEquals(1, warnings.size(), "once per change, not every interval");
        assertTrue(warnings.get(0).contains("setting=securedoc.platform-admin.watched-folder-tenant-id"));
        assertFalse(warnings.get(0).contains("Jane"));
        verifyNoInteractions(intake);
    }

    @Test
    void aManualScanWithoutTheLeaseSaysWhyNothingWasScanned() {
        admin.setWatchedFolderCoordinationEnabled(true);
        // No node id: this node may not take the scan lease.
        var principal = com.securedoc.platform.application.context.AuthenticatedPrincipal.of(
                com.securedoc.platform.domain.identity.ActorId.generate(),
                com.securedoc.platform.domain.identity.TenantId.of(UUID.fromString(admin.getWatchedFolderTenantId())),
                "it-admin", java.util.Set.of());

        var response = scheduler.scanManual(principal, com.securedoc.platform.domain.identity.CorrelationId.generate());

        assertEquals("NOT_CONFIGURED", response.outcome());
        assertEquals(0, response.discovered());
        verifyNoInteractions(intake);
    }
}
