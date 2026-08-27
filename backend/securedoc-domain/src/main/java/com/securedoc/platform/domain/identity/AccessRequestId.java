package com.securedoc.platform.domain.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Unique identifier for an access request to a vault entry.
 */
public record AccessRequestId(UUID value) {

    public AccessRequestId {
        Objects.requireNonNull(value, "AccessRequestId value must not be null");
    }

    public static AccessRequestId generate() {
        return new AccessRequestId(UUID.randomUUID());
    }

    public static AccessRequestId of(UUID value) {
        return new AccessRequestId(value);
    }

    public static AccessRequestId fromString(String value) {
        Objects.requireNonNull(value, "AccessRequestId string must not be null");
        return new AccessRequestId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
