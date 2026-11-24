package com.securedoc.platform.domain.business;

/**
 * Task 0064: an entity's vision settings.
 *
 * @param enabled      routed pages are read by the vision model (on by default). Off, or without a usable model, the
 *                     same pages go to a person: routing itself is never off, so no page passes unread.
 * @param ocrThreshold a page whose OCR confidence is below this is routed (default 0.85, owner, 0033 answers)
 */
public record VisionSettings(boolean enabled, double ocrThreshold) {
    public static final double MIN_THRESHOLD = 0.50;
    public static final double MAX_THRESHOLD = 0.99;
    public static final VisionSettings DEFAULTS = new VisionSettings(true, 0.85);

    public VisionSettings {
        if (!(ocrThreshold >= MIN_THRESHOLD && ocrThreshold <= MAX_THRESHOLD)) {
            throw new IllegalArgumentException("The vision routing threshold must be between 0.50 and 0.99");
        }
    }
}
