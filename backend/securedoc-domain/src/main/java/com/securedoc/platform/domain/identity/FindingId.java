package com.securedoc.platform.domain.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Unique identifier for a sensitive-data finding.
 */
public record FindingId(UUID value) {

    public FindingId {
        Objects.requireNonNull(value, "FindingId value must not be null");
    }

    public static FindingId generate() {
        return new FindingId(UUID.randomUUID());
    }

    public static FindingId of(UUID value) {
        return new FindingId(value);
    }

    public static FindingId fromString(String value) {
        Objects.requireNonNull(value, "FindingId string must not be null");
        return new FindingId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
