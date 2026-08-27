package com.securedoc.platform.domain.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Unique identifier for a specific version of a policy.
 */
public record PolicyVersionId(UUID value) {

    public PolicyVersionId {
        Objects.requireNonNull(value, "PolicyVersionId value must not be null");
    }

    public static PolicyVersionId generate() {
        return new PolicyVersionId(UUID.randomUUID());
    }

    public static PolicyVersionId of(UUID value) {
        return new PolicyVersionId(value);
    }

    public static PolicyVersionId fromString(String value) {
        Objects.requireNonNull(value, "PolicyVersionId string must not be null");
        return new PolicyVersionId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
