package com.securedoc.platform.application.intake.polling;

import com.securedoc.platform.domain.integration.IntegrationConnection;
import com.securedoc.platform.domain.integration.IntegrationPollingScheduleType;

import java.time.*;

public final class PollingScheduleCalculator {
    private PollingScheduleCalculator() {}

    public static boolean isDue(IntegrationConnection connection, Instant latestStartedAt, Instant now) {
        Instant next = nextRunAt(connection, latestStartedAt, now);
        return next != null && !next.isAfter(now);
    }

    /** Returns the next scheduled execution based on the last actual start, or connection creation when never run. */
    public static Instant nextRunAt(IntegrationConnection connection, Instant latestStartedAt, Instant now) {
        if (!connection.enabled() || !connection.pollingSource() || connection.pollingScheduleType() == null) return null;
        Instant base = latestStartedAt == null ? connection.createdAt() : latestStartedAt;
        if (connection.pollingScheduleType() == IntegrationPollingScheduleType.INTERVAL) {
            return base.plusSeconds((long) effectiveInterval(connection) * 60L);
        }
        ZoneId zone = ZoneId.of(connection.pollingTimeZone());
        ZonedDateTime baseLocal = base.atZone(zone);
        ZonedDateTime target = baseLocal.toLocalDate().atTime(connection.pollingLocalTime()).atZone(zone);
        if (!target.isAfter(baseLocal)) target = target.plusDays(1);
        return target.toInstant();
    }

    private static int effectiveInterval(IntegrationConnection connection) {
        return connection.pollingIntervalMinutes() == null
                ? IntegrationConnection.DEFAULT_POLLING_INTERVAL_MINUTES : connection.pollingIntervalMinutes();
    }
}
