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

    private IntegrationConnection polling(IntegrationPollingScheduleType type, Integer interval, LocalTime localTime, String zone) {
        return IntegrationConnection.create(TenantId.generate(), "Polling Source", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API,
                IntegrationAuthenticationType.NONE, null, "https://source.example.test", null, null,
                60, 2, 24, 100, 262144000L, 2000,
                type, interval, localTime, zone, 100,
                IntegrationAcknowledgementMode.SOURCE_PICKUP_ACK, true, base);
    }
}
