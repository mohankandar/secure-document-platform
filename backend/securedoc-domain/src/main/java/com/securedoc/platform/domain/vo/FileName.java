package com.securedoc.platform.domain.vo;

import java.util.Objects;
import java.util.Optional;

/**
 * Represents a validated file name.
 * Enforces that file names are non-blank and do not contain path separators.
 */
public record FileName(String value) {

    private static final int MAX_LENGTH = 255;

    public FileName {
        Objects.requireNonNull(value, "FileName must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("FileName must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "FileName must not exceed " + MAX_LENGTH + " characters, got: " + value.length());
        }
        if (value.contains("/") || value.contains("\\")) {
            throw new IllegalArgumentException("FileName must not contain path separators: " + value);
        }
    }

    public static FileName of(String value) {
        return new FileName(value);
    }

    /**
     * Returns the file extension (without the dot), or empty if none.
     */
    public Optional<String> extension() {
        int lastDot = value.lastIndexOf('.');
        if (lastDot < 0 || lastDot == value.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(value.substring(lastDot + 1));
    }

    /**
     * Returns the base name without extension.
     */
    public String baseName() {
        int lastDot = value.lastIndexOf('.');
        if (lastDot < 0) {
            return value;
        }
        return value.substring(0, lastDot);
    }

    @Override
    public String toString() {
        return value;
    }
}
