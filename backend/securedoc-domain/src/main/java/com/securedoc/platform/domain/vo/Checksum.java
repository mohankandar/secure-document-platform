package com.securedoc.platform.domain.vo;

import java.util.Objects;

/**
 * Represents a content checksum with its algorithm identifier.
 * Used for integrity verification of documents and artifacts.
 */
public record Checksum(String algorithm, String value) {

    public Checksum {
        Objects.requireNonNull(algorithm, "Checksum algorithm must not be null");
        Objects.requireNonNull(value, "Checksum value must not be null");
        if (algorithm.isBlank()) {
            throw new IllegalArgumentException("Checksum algorithm must not be blank");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("Checksum value must not be blank");
        }
    }

    public static Checksum sha256(String value) {
        return new Checksum("SHA-256", value);
    }

    public static Checksum md5(String value) {
        return new Checksum("MD5", value);
    }

    public static Checksum of(String algorithm, String value) {
        return new Checksum(algorithm, value);
    }

    public boolean matches(Checksum other) {
        return this.algorithm.equalsIgnoreCase(other.algorithm)
                && this.value.equalsIgnoreCase(other.value);
    }

    @Override
    public String toString() {
        return algorithm + ":" + value;
    }
}
