package com.securedoc.platform.domain.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Correlation identifier for tracing related operations across the system.
 */
public record CorrelationId(UUID value) {

    public CorrelationId {
        Objects.requireNonNull(value, "CorrelationId value must not be null");
    }

    public static CorrelationId generate() {
        return new CorrelationId(UUID.randomUUID());
    }

    public static CorrelationId of(UUID value) {
        return new CorrelationId(value);
    }

    public static CorrelationId fromString(String value) {
        Objects.requireNonNull(value, "CorrelationId string must not be null");
        return new CorrelationId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
