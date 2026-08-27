package com.securedoc.platform.domain.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Unique identifier for a processing artifact.
 */
public record ArtifactId(UUID value) {

    public ArtifactId {
        Objects.requireNonNull(value, "ArtifactId value must not be null");
    }

    public static ArtifactId generate() {
        return new ArtifactId(UUID.randomUUID());
    }

    public static ArtifactId of(UUID value) {
        return new ArtifactId(value);
    }

    public static ArtifactId fromString(String value) {
        Objects.requireNonNull(value, "ArtifactId string must not be null");
        return new ArtifactId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
