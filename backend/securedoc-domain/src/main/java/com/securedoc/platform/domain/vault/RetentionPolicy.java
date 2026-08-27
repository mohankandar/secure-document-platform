package com.securedoc.platform.domain.vault;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Defines how long a vault entry must be retained and its disposal rules.
 */
public record RetentionPolicy(
        Duration minimumRetention,
        Duration maximumRetention,
        boolean legalHold
) {

    public RetentionPolicy {
        Objects.requireNonNull(minimumRetention, "Minimum retention must not be null");
        if (minimumRetention.isNegative()) {
            throw new IllegalArgumentException("Minimum retention must not be negative");
        }
        if (maximumRetention != null && maximumRetention.compareTo(minimumRetention) < 0) {
            throw new IllegalArgumentException(
                    "Maximum retention must be >= minimum retention");
        }
    }

    public static RetentionPolicy of(Duration minimum, Duration maximum, boolean legalHold) {
        return new RetentionPolicy(minimum, maximum, legalHold);
    }

    public static RetentionPolicy indefinite() {
        return new RetentionPolicy(Duration.ofDays(365 * 100), null, false);
    }

    public static RetentionPolicy withLegalHold(Duration minimum) {
        return new RetentionPolicy(minimum, null, true);
    }

    public Optional<Duration> maximumRetentionOpt() {
        return Optional.ofNullable(maximumRetention);
    }

    public boolean isIndefinite() {
        return maximumRetention == null && !legalHold;
    }
}
