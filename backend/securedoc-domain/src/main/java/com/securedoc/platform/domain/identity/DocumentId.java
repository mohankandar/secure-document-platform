package com.securedoc.platform.domain.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Unique identifier for a document within a package.
 */
public record DocumentId(UUID value) {

    public DocumentId {
        Objects.requireNonNull(value, "DocumentId value must not be null");
    }

    public static DocumentId generate() {
        return new DocumentId(UUID.randomUUID());
    }

    public static DocumentId of(UUID value) {
        return new DocumentId(value);
    }

    public static DocumentId fromString(String value) {
        Objects.requireNonNull(value, "DocumentId string must not be null");
        return new DocumentId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
