package com.securedoc.platform.domain.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Unique identifier for a policy.
 */
public record PolicyId(UUID value) {

    public PolicyId {
        Objects.requireNonNull(value, "PolicyId value must not be null");
    }

    public static PolicyId generate() {
        return new PolicyId(UUID.randomUUID());
    }

    public static PolicyId of(UUID value) {
        return new PolicyId(value);
    }

    public static PolicyId fromString(String value) {
        Objects.requireNonNull(value, "PolicyId string must not be null");
        return new PolicyId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
