package com.securedoc.platform.application.intake.polling;

import com.securedoc.platform.domain.integration.IntegrationConnection;
import com.securedoc.platform.domain.integration.IntegrationPollingScheduleType;

import java.time.*;

public final class PollingScheduleCalculator {
    private PollingScheduleCalculator() {}

    public static boolean isDue(IntegrationConnection connection, Instant latestStartedAt, Instant now) {
        return isDue(connection, latestStartedAt, now, null);
    }

    /** @param entityZone the entity's time zone (task 0058), for a daily schedule without one of its own; null when it has none */
    public static boolean isDue(IntegrationConnection connection, Instant latestStartedAt, Instant now, ZoneId entityZone) {
        Instant next = nextRunAt(connection, latestStartedAt, now, entityZone);
        return next != null && !next.isAfter(now);
    }

    /** Returns the next scheduled execution based on the last actual start, or connection creation when never run. */
    public static Instant nextRunAt(IntegrationConnection connection, Instant latestStartedAt, Instant now) {
        return nextRunAt(connection, latestStartedAt, now, null);
    }

    /**
     * As above. A daily schedule runs once a day at its time of day, in its own zone or else the entity's; without either it
     * never runs (nothing is guessed). On the day the clocks go forward over that time it runs right after the gap, and on
     * the day they go back it runs once.
     */
    public static Instant nextRunAt(IntegrationConnection connection, Instant latestStartedAt, Instant now, ZoneId entityZone) {
        if (!connection.enabled() || !connection.pollingSource() || connection.pollingScheduleType() == null) return null;
        Instant base = latestStartedAt == null ? connection.createdAt() : latestStartedAt;
        if (connection.pollingScheduleType() == IntegrationPollingScheduleType.INTERVAL) {
            return base.plusSeconds((long) effectiveInterval(connection) * 60L);
        }
        ZoneId zone = connection.pollingTimeZone() == null ? entityZone : ZoneId.of(connection.pollingTimeZone());
        if (zone == null) return null;
        ZonedDateTime baseLocal = base.atZone(zone);
        ZonedDateTime target = baseLocal.toLocalDate().atTime(connection.pollingLocalTime()).atZone(zone);
        if (!target.isAfter(baseLocal)) {
            target = baseLocal.toLocalDate().plusDays(1).atTime(connection.pollingLocalTime()).atZone(zone);
        }
        return target.toInstant();
    }

    private static int effectiveInterval(IntegrationConnection connection) {
        return connection.pollingIntervalMinutes() == null
                ? IntegrationConnection.DEFAULT_POLLING_INTERVAL_MINUTES : connection.pollingIntervalMinutes();
    }
}
