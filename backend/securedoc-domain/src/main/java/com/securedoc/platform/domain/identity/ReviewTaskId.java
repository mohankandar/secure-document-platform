package com.securedoc.platform.domain.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Unique identifier for a human review task.
 */
public record ReviewTaskId(UUID value) {

    public ReviewTaskId {
        Objects.requireNonNull(value, "ReviewTaskId value must not be null");
    }

    public static ReviewTaskId generate() {
        return new ReviewTaskId(UUID.randomUUID());
    }

    public static ReviewTaskId of(UUID value) {
        return new ReviewTaskId(value);
    }

    public static ReviewTaskId fromString(String value) {
        Objects.requireNonNull(value, "ReviewTaskId string must not be null");
        return new ReviewTaskId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
