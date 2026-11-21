package com.securedoc.platform.application.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0028: the export job settings and their defaults. */
class ExportJobSettingsTest {

    @Test
    void theDefaultsAreTheOnesTheOwnerAgreed() {
        ExportJobSettings s = ExportJobSettings.defaults(ExportMode.JOB);
        assertEquals(List.of(Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(4),
                Duration.ofHours(12)), s.retrySchedule());
        assertEquals(6, s.scheduledTries());
        assertEquals(3, s.rebuildBeforeRetry());
        assertEquals(3, s.failingCasesToPause());
        assertEquals(Duration.ofMinutes(10), s.failingCasesWindow());
        assertEquals(Duration.ofSeconds(60), s.rateLimitDefaultWait());
        assertEquals(Duration.ofMinutes(10), s.rateLimitRecovery());
        assertEquals(Duration.ofMinutes(2), s.allUnreachableWindow());
        assertEquals(Duration.ofMinutes(5), s.probeInterval());
        assertEquals(3, s.maxAttempts());
    }

    @Test
    void theScheduleIsAListOfDelaysAndAnythingElseIsRefused() {
        assertEquals(List.of(Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofHours(2), Duration.ofMillis(1500)),
                ExportJobSettings.parseSchedule(" 30s, 5m ,2h,1500"));
        assertEquals(ExportJobSettings.DEFAULT_RETRY_SCHEDULE, ExportJobSettings.parseSchedule(""));
        for (String bad : List.of("5x", "m", "5m,,abc", ",")) {
            assertThrows(IllegalArgumentException.class, () -> ExportJobSettings.parseSchedule(bad), bad);
        }
        ExportJobSettings d = ExportJobSettings.defaults(ExportMode.JOB);
        assertThrows(IllegalArgumentException.class, () -> new ExportJobSettings(d.mode(), d.retryDelay(),
                d.maxAttempts(), d.pauseAfterFailures(), d.probeInterval(), d.claimLease(), d.workersPerDestination(),
                d.workersTotal(), List.of(), 3, 3, d.failingCasesWindow(), d.rateLimitDefaultWait(),
                d.rateLimitRecovery(), d.allUnreachableWindow()), "an empty schedule");
        assertThrows(IllegalArgumentException.class, () -> new ExportJobSettings(d.mode(), d.retryDelay(),
                d.maxAttempts(), d.pauseAfterFailures(), d.probeInterval(), d.claimLease(), d.workersPerDestination(),
                d.workersTotal(), d.retrySchedule(), 3, 1, d.failingCasesWindow(), d.rateLimitDefaultWait(),
                d.rateLimitRecovery(), d.allUnreachableWindow()), "one case is not several cases");
    }
}
