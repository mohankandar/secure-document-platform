package com.securedoc.platform.application.pipeline;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Export job settings (task 0027; task 0028 replaced pausing by counting with pausing by evidence).
 *
 * <ul>
 *   <li>{@code retrySchedule}: a single failing case (LV-DLV-001/002 with a passing check, LV-DLV-007/008) waits
 *       these delays between tries, then goes to IT. Default 5 min, 15 min, 1 h, 4 h, 12 h.</li>
 *   <li>{@code rebuildBeforeRetry}: the export is rebuilt once before this retry (default the 3rd).</li>
 *   <li>{@code failingCasesToPause} / {@code failingCasesWindow}: the backup rule, "answering but failing": failures
 *       from this many different cases within the window pause the destination though its check passes
 *       (default 3 in 10 minutes).</li>
 *   <li>{@code rateLimitDefaultWait}: a 429 without Retry-After waits this long (default 60 s);
 *       {@code rateLimitRecovery}: a worker comes back after this long without a 429 (default 10 min).</li>
 *   <li>{@code allUnreachableWindow}: every destination failing its check within this window raises the
 *       "can't reach its destinations" alert instead of pausing (default 2 min).</li>
 *   <li>{@code maxAttempts}: claims that may run out (a server stopping mid-job) before LV-PRC-008 (default 3).</li>
 *   <li>{@code probeInterval}: a paused destination, and the alert, are checked this often (default 5 min).</li>
 *   <li>{@code retryDelay} and {@code pauseAfterFailures}: task 0027 settings, still read but no longer used.</li>
 * </ul>
 */
public record ExportJobSettings(
        ExportMode mode,
        Duration retryDelay,
        int maxAttempts,
        int pauseAfterFailures,
        Duration probeInterval,
        Duration claimLease,
        int workersPerDestination,
        int workersTotal,
        List<Duration> retrySchedule,
        int rebuildBeforeRetry,
        int failingCasesToPause,
        Duration failingCasesWindow,
        Duration rateLimitDefaultWait,
        Duration rateLimitRecovery,
        Duration allUnreachableWindow) {

    public static final List<Duration> DEFAULT_RETRY_SCHEDULE = List.of(Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1), Duration.ofHours(4), Duration.ofHours(12));

    public ExportJobSettings {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(retryDelay, "retryDelay");
        Objects.requireNonNull(probeInterval, "probeInterval");
        Objects.requireNonNull(claimLease, "claimLease");
        Objects.requireNonNull(retrySchedule, "retrySchedule");
        Objects.requireNonNull(failingCasesWindow, "failingCasesWindow");
        Objects.requireNonNull(rateLimitDefaultWait, "rateLimitDefaultWait");
        Objects.requireNonNull(rateLimitRecovery, "rateLimitRecovery");
        Objects.requireNonNull(allUnreachableWindow, "allUnreachableWindow");
        retrySchedule = List.copyOf(retrySchedule);
        if (retryDelay.isNegative()) throw new IllegalArgumentException("retryDelay must not be negative");
        if (probeInterval.isNegative()) throw new IllegalArgumentException("probeInterval must not be negative");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
        if (pauseAfterFailures < 1) throw new IllegalArgumentException("pauseAfterFailures must be >= 1");
        if (claimLease.toMillis() < 1000) throw new IllegalArgumentException("claimLease must be >= 1 second");
        if (workersPerDestination < 1) throw new IllegalArgumentException("workersPerDestination must be >= 1");
        if (workersTotal < 1) throw new IllegalArgumentException("workersTotal must be >= 1");
        if (retrySchedule.isEmpty()) throw new IllegalArgumentException("retrySchedule must have at least one delay");
        if (retrySchedule.stream().anyMatch(d -> d.isNegative() || d.isZero())) {
            throw new IllegalArgumentException("retrySchedule delays must be positive");
        }
        if (rebuildBeforeRetry < 1) throw new IllegalArgumentException("rebuildBeforeRetry must be >= 1");
        if (failingCasesToPause < 2) throw new IllegalArgumentException("failingCasesToPause must be >= 2");
        if (failingCasesWindow.isNegative() || failingCasesWindow.isZero()) {
            throw new IllegalArgumentException("failingCasesWindow must be positive");
        }
        if (rateLimitDefaultWait.isNegative()) throw new IllegalArgumentException("rateLimitDefaultWait must not be negative");
        if (rateLimitRecovery.isNegative() || rateLimitRecovery.isZero()) {
            throw new IllegalArgumentException("rateLimitRecovery must be positive");
        }
        if (allUnreachableWindow.isNegative() || allUnreachableWindow.isZero()) {
            throw new IllegalArgumentException("allUnreachableWindow must be positive");
        }
    }

    /** The task 0027 settings with the task 0028 defaults for the rest. */
    public ExportJobSettings(ExportMode mode, Duration retryDelay, int maxAttempts, int pauseAfterFailures,
                             Duration probeInterval, Duration claimLease, int workersPerDestination, int workersTotal) {
        this(mode, retryDelay, maxAttempts, pauseAfterFailures, probeInterval, claimLease, workersPerDestination,
                workersTotal, DEFAULT_RETRY_SCHEDULE, 3, 3, Duration.ofMinutes(10), Duration.ofSeconds(60),
                Duration.ofMinutes(10), Duration.ofMinutes(2));
    }

    public static ExportJobSettings defaults(ExportMode mode) {
        return new ExportJobSettings(mode, Duration.ofMinutes(5), 3, 3, Duration.ofMinutes(5),
                Duration.ofMinutes(2), 4, 8);
    }

    /** Tries a single failing case gets before IT: the first one plus one per delay in the schedule. */
    public int scheduledTries() {
        return retrySchedule.size() + 1;
    }

    /** Parses a list of delays such as {@code 5m,15m,1h,4h,12h} (units s, m, h; a bare number is milliseconds). */
    public static List<Duration> parseSchedule(String value) {
        if (value == null || value.isBlank()) return DEFAULT_RETRY_SCHEDULE;
        List<Duration> delays = new java.util.ArrayList<>();
        for (String raw : value.split(",")) {
            String part = raw.trim().toLowerCase(java.util.Locale.ROOT);
            if (part.isEmpty()) continue;
            try {
                char unit = part.charAt(part.length() - 1);
                String number = Character.isDigit(unit) ? part : part.substring(0, part.length() - 1).trim();
                long n = Long.parseLong(number);
                delays.add(switch (unit) {
                    case 's' -> Duration.ofSeconds(n);
                    case 'm' -> Duration.ofMinutes(n);
                    case 'h' -> Duration.ofHours(n);
                    default -> {
                        if (!Character.isDigit(unit)) throw new IllegalArgumentException("unit");
                        yield Duration.ofMillis(n);
                    }
                });
            } catch (RuntimeException unreadable) {
                throw new IllegalArgumentException(
                        "securedoc.processing.export-jobs.retry-schedule must be a list such as 5m,15m,1h,4h,12h");
            }
        }
        if (delays.isEmpty()) throw new IllegalArgumentException(
                "securedoc.processing.export-jobs.retry-schedule must have at least one delay");
        return List.copyOf(delays);
    }
}
