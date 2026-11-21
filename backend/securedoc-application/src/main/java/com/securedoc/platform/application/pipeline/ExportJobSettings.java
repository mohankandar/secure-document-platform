package com.securedoc.platform.application.pipeline;

import java.time.Duration;
import java.util.Objects;

/**
 * Export job settings (task 0027; design section 5). Defaults: 5 minutes between tries, 3 tries, pause a destination
 * after 3 failures in a row, probe a paused destination every 5 minutes, a 2-minute renewing claim, 4 workers per
 * destination and 8 in all.
 */
public record ExportJobSettings(
        ExportMode mode,
        Duration retryDelay,
        int maxAttempts,
        int pauseAfterFailures,
        Duration probeInterval,
        Duration claimLease,
        int workersPerDestination,
        int workersTotal) {

    public ExportJobSettings {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(retryDelay, "retryDelay");
        Objects.requireNonNull(probeInterval, "probeInterval");
        Objects.requireNonNull(claimLease, "claimLease");
        if (retryDelay.isNegative()) throw new IllegalArgumentException("retryDelay must not be negative");
        if (probeInterval.isNegative()) throw new IllegalArgumentException("probeInterval must not be negative");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (pauseAfterFailures < 1) throw new IllegalArgumentException("pauseAfterFailures must be >= 1");
        if (claimLease.toMillis() < 1000) throw new IllegalArgumentException("claimLease must be >= 1 second");
        if (workersPerDestination < 1) throw new IllegalArgumentException("workersPerDestination must be >= 1");
        if (workersTotal < 1) throw new IllegalArgumentException("workersTotal must be >= 1");
    }

    public static ExportJobSettings defaults(ExportMode mode) {
        return new ExportJobSettings(mode, Duration.ofMinutes(5), 3, 3, Duration.ofMinutes(5),
                Duration.ofMinutes(2), 4, 8);
    }

    public boolean jobMode() {
        return mode == ExportMode.JOB;
    }
}
