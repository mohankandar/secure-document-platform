package com.securedoc.platform.application.review.result;

/**
 * Normalized page coordinates exposed to the focused-review UI for visual highlighting/editing.
 * Values are percentages of the rendered page expressed as 0.0-1.0.
 */
public record ReviewBoundingBoxSummary(
        double left,
        double top,
        double width,
        double height
) {}
