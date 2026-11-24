package com.securedoc.platform.application.intake.polling;

import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.*;
import org.junit.jupiter.api.Test;

import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

class PollingScheduleCalculatorTest {
    private final Instant base = Instant.parse("2026-09-03T05:00:00Z");

    @Test
    void intervalScheduleIsDueOnlyAfterConfiguredInterval() {
        var connection = polling(IntegrationPollingScheduleType.INTERVAL, 60, null, null);
        assertFalse(PollingScheduleCalculator.isDue(connection, null, base));
        assertFalse(PollingScheduleCalculator.isDue(connection, null, base.plusSeconds(3599)));
        assertTrue(PollingScheduleCalculator.isDue(connection, null, base.plusSeconds(3600)));
        assertFalse(PollingScheduleCalculator.isDue(connection, base, base.plusSeconds(3599)));
        assertTrue(PollingScheduleCalculator.isDue(connection, base, base.plusSeconds(3600)));
        assertEquals(base.plusSeconds(3600), PollingScheduleCalculator.nextRunAt(connection, base, base));
    }

    @Test
    void dailyScheduleUsesConfiguredTimeZoneAndDoesNotRunTwiceSameDay() {
        var connection = polling(IntegrationPollingScheduleType.DAILY, null, LocalTime.of(1, 0), "America/New_York");
        Instant before = Instant.parse("2026-09-03T04:30:00Z"); // 00:30 EDT
        Instant after = Instant.parse("2026-09-03T05:05:00Z");  // 01:05 EDT
        assertFalse(PollingScheduleCalculator.isDue(connection, Instant.parse("2026-09-02T05:05:00Z"), before));
        assertTrue(PollingScheduleCalculator.isDue(connection, Instant.parse("2026-09-02T05:05:00Z"), after));
        assertFalse(PollingScheduleCalculator.isDue(connection, Instant.parse("2026-09-03T05:01:00Z"), after));
        assertEquals(Instant.parse("2026-09-04T05:00:00Z"), PollingScheduleCalculator.nextRunAt(connection, Instant.parse("2026-09-03T05:01:00Z"), after));
    }

    /** Task 0058: without a zone of its own a daily schedule follows the entity's; without either it never runs. */
    @Test
    void aDailyScheduleWithoutAZoneFollowsTheEntitys() {
        var connection = polling(IntegrationPollingScheduleType.DAILY, null, LocalTime.of(1, 0), null);
        Instant ran = Instant.parse("2026-09-02T05:05:00Z");
        java.time.ZoneId newYork = java.time.ZoneId.of("America/New_York"), london = java.time.ZoneId.of("Europe/London");
        assertEquals(Instant.parse("2026-09-03T05:00:00Z"), PollingScheduleCalculator.nextRunAt(connection, ran, ran, newYork));
        assertEquals(Instant.parse("2026-09-03T00:00:00Z"), PollingScheduleCalculator.nextRunAt(connection, ran, ran, london));
        org.junit.jupiter.api.Assertions.assertNull(PollingScheduleCalculator.nextRunAt(connection, ran, ran, null));
        assertFalse(PollingScheduleCalculator.isDue(connection, ran, ran.plusSeconds(864000), null));
        var own = polling(IntegrationPollingScheduleType.DAILY, null, LocalTime.of(1, 0), "America/New_York");
        assertEquals(Instant.parse("2026-09-03T05:00:00Z"), PollingScheduleCalculator.nextRunAt(own, ran, ran, london), "its own comes first");
    }

    /** Task 0058: the days the clocks change: once a day, and the next day back at its own time. */
    @Test
    void onTheDaysTheClocksChangeADailyScheduleRunsOnce() {
        java.time.ZoneId zone = java.time.ZoneId.of("America/New_York");
        var gap = polling(IntegrationPollingScheduleType.DAILY, null, LocalTime.of(2, 30), null);
        Instant saturday = Instant.parse("2026-03-07T07:30:00Z"); // 02:30 EST
        Instant sunday = PollingScheduleCalculator.nextRunAt(gap, saturday, saturday, zone);
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), sunday); // 02:30 does not exist: 03:30 EDT
        assertEquals(Instant.parse("2026-03-09T06:30:00Z"), PollingScheduleCalculator.nextRunAt(gap, sunday, sunday, zone)); // 02:30 EDT
        var twice = polling(IntegrationPollingScheduleType.DAILY, null, LocalTime.of(1, 30), null);
        Instant first = Instant.parse("2026-11-01T05:30:00Z"); // 01:30 EDT, the first of two
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"), PollingScheduleCalculator.nextRunAt(twice, first, first, zone)); // 01:30 EST
    }

    private IntegrationConnection polling(IntegrationPollingScheduleType type, Integer interval, LocalTime localTime, String zone) {
        return IntegrationConnection.create(TenantId.generate(), "Polling Source", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API,
                IntegrationAuthenticationType.NONE, null, "https://source.example.test", null, null,
                60, 2, 24, 50, 262144000L, 500,
                type, interval, localTime, zone, 100,
                IntegrationAcknowledgementMode.SOURCE_PICKUP_ACK, true, base);
    }
}
