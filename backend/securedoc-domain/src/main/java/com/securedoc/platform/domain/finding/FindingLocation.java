package com.securedoc.platform.domain.finding;

import java.util.Objects;

/**
 * Locates a finding within a document page.
 * Supports both text offset (character positions) and spatial (bounding box) location.
 */
public record FindingLocation(
        int pageNumber,
        int startOffset,
        int endOffset,
        BoundingBox boundingBox
) {

    public FindingLocation {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("Page number must be >= 1, got: " + pageNumber);
        }
        if (startOffset < 0) {
            throw new IllegalArgumentException("Start offset must be >= 0, got: " + startOffset);
        }
        if (endOffset < startOffset) {
            throw new IllegalArgumentException(
                    "End offset must be >= start offset, got start=" + startOffset + " end=" + endOffset);
        }
    }

    /**
     * Creates a text-only location without bounding box.
     */
    public static FindingLocation textOnly(int pageNumber, int startOffset, int endOffset) {
        return new FindingLocation(pageNumber, startOffset, endOffset, null);
    }

    /**
     * Creates a location with both text offsets and spatial coordinates.
     */
    public static FindingLocation withBounds(int pageNumber, int startOffset, int endOffset,
                                              BoundingBox boundingBox) {
        Objects.requireNonNull(boundingBox, "BoundingBox must not be null");
        return new FindingLocation(pageNumber, startOffset, endOffset, boundingBox);
    }

    public boolean hasBoundingBox() {
        return boundingBox != null;
    }

    /**
     * Bounding box coordinates on a page (normalized 0.0-1.0).
     * Width and height must be strictly positive.
     * The box must not extend beyond the page boundary (left+width <= 1.0, top+height <= 1.0).
     */
    public record BoundingBox(double left, double top, double width, double height) {

        public BoundingBox {
            if (left < 0.0 || left > 1.0) {
                throw new IllegalArgumentException("Left must be 0.0-1.0, got: " + left);
            }
            if (top < 0.0 || top > 1.0) {
                throw new IllegalArgumentException("Top must be 0.0-1.0, got: " + top);
            }
            if (width <= 0.0 || width > 1.0) {
                throw new IllegalArgumentException("Width must be > 0.0 and <= 1.0, got: " + width);
            }
            if (height <= 0.0 || height > 1.0) {
                throw new IllegalArgumentException("Height must be > 0.0 and <= 1.0, got: " + height);
            }
            if (left + width > 1.0) {
                throw new IllegalArgumentException(
                        "left + width must be <= 1.0, got: " + left + " + " + width + " = " + (left + width));
            }
            if (top + height > 1.0) {
                throw new IllegalArgumentException(
                        "top + height must be <= 1.0, got: " + top + " + " + height + " = " + (top + height));
            }
        }

        public static BoundingBox of(double left, double top, double width, double height) {
            return new BoundingBox(left, top, width, height);
        }
    }
}
