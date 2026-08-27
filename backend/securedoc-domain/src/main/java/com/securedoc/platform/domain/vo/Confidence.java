package com.securedoc.platform.domain.vo;

/**
 * Represents a confidence score between 0.0 and 1.0 inclusive.
 * Used for detection results, AI predictions, and matching scores.
 */
public record Confidence(double value) {

    public Confidence {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    "Confidence must be between 0.0 and 1.0 inclusive, got: " + value);
        }
    }

    public static Confidence of(double value) {
        return new Confidence(value);
    }

    public static Confidence full() {
        return new Confidence(1.0);
    }

    public static Confidence zero() {
        return new Confidence(0.0);
    }

    public boolean isAboveThreshold(double threshold) {
        return value >= threshold;
    }

    @Override
    public String toString() {
        return String.format("%.4f", value);
    }
}
