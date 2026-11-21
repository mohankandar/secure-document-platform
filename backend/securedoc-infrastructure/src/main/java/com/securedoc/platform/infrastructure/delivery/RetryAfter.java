package com.securedoc.platform.infrastructure.delivery;

import com.securedoc.platform.application.exception.TechnicalDependencyException;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Task 0028: a destination's 429 ("slow down") and its {@code Retry-After}. The failure keeps its code and retry
 * behaviour; it is only marked, so an export job waits instead of counting an attempt. The wait is delta-seconds or an
 * HTTP-date, capped at one hour; none (missing, unreadable, zero or past) leaves the job's default wait.
 */
public final class RetryAfter {
    static final Duration MAX_WAIT = Duration.ofHours(1);

    private RetryAfter() { }

    /** The wait a {@code Retry-After} value asks for, from {@code now}; null when it gives none. */
    public static Duration parse(String value, Instant now) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        Duration wait;
        try {
            wait = Duration.ofSeconds(Long.parseLong(v));
        } catch (NumberFormatException notSeconds) {
            try {
                wait = Duration.between(now, ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            } catch (RuntimeException unreadable) {
                return null;
            }
        }
        if (wait.isNegative() || wait.isZero()) return null;
        return wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait;
    }

    /** Marks a failure raised for a 429 answer with the wait it asked for; any other answer is returned as it is. */
    public static TechnicalDependencyException mark(TechnicalDependencyException failure, HttpResponse<?> response) {
        if (response == null || response.statusCode() != 429) return failure;
        return failure.asRateLimited(parse(response.headers().firstValue("Retry-After").orElse(null), Instant.now()));
    }
}
