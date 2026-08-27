package com.securedoc.platform.domain.document;

import java.util.Objects;
import java.util.Optional;

/**
 * Represents a single page within a document.
 * Immutable value object carrying page-level metadata.
 */
public record Page(int pageNumber, int width, int height, String extractedText) {

    public Page {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("Page number must be >= 1, got: " + pageNumber);
        }
        if (width < 0) {
            throw new IllegalArgumentException("Page width must be >= 0, got: " + width);
        }
        if (height < 0) {
            throw new IllegalArgumentException("Page height must be >= 0, got: " + height);
        }
    }

    /**
     * Creates a page with dimensions but no extracted text yet.
     */
    public static Page of(int pageNumber, int width, int height) {
        return new Page(pageNumber, width, height, null);
    }

    /**
     * Creates a page with extracted text.
     */
    public static Page withText(int pageNumber, int width, int height, String extractedText) {
        Objects.requireNonNull(extractedText, "Extracted text must not be null; use of() for pages without text");
        return new Page(pageNumber, width, height, extractedText);
    }

    public Optional<String> text() {
        return Optional.ofNullable(extractedText);
    }

    public boolean hasText() {
        return extractedText != null && !extractedText.isBlank();
    }
}
