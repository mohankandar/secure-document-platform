package com.securedoc.platform.domain.vo;

import java.util.Objects;

/**
 * Represents a MIME type (content type) for a file or artifact.
 * Validates basic structure: type/subtype format.
 */
public record MimeType(String value) {

    public MimeType {
        Objects.requireNonNull(value, "MimeType must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("MimeType must not be blank");
        }
        if (!value.contains("/")) {
            throw new IllegalArgumentException("MimeType must contain type/subtype format: " + value);
        }
    }

    public static MimeType of(String value) {
        return new MimeType(value);
    }

    public static MimeType pdf() {
        return new MimeType("application/pdf");
    }

    public static MimeType png() {
        return new MimeType("image/png");
    }

    public static MimeType jpeg() {
        return new MimeType("image/jpeg");
    }

    public static MimeType plainText() {
        return new MimeType("text/plain");
    }

    public String type() {
        return value.substring(0, value.indexOf('/'));
    }

    public String subtype() {
        return value.substring(value.indexOf('/') + 1);
    }

    public boolean isImage() {
        return value.startsWith("image/");
    }

    public boolean isPdf() {
        return "application/pdf".equals(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
