package com.securedoc.platform.domain.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Unique identifier for a document package.
 */
public record PackageId(UUID value) {

    public PackageId {
        Objects.requireNonNull(value, "PackageId value must not be null");
    }

    public static PackageId generate() {
        return new PackageId(UUID.randomUUID());
    }

    public static PackageId of(UUID value) {
        return new PackageId(value);
    }

    public static PackageId fromString(String value) {
        Objects.requireNonNull(value, "PackageId string must not be null");
        return new PackageId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
