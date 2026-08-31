package com.securedoc.platform.application.detection.service;

import com.securedoc.platform.application.port.out.TextExtractionPort.PageText;
import com.securedoc.platform.application.port.out.TextExtractionPort.TextSpan;
import com.securedoc.platform.domain.finding.FindingLocation;

import java.util.List;
import java.util.Optional;

/**
 * Maps a detected character range on a page to a normalized bounding box by
 * unioning the positional spans that overlap the range.
 *
 * <p>Provider-neutral: any extraction/OCR provider that emits {@link TextSpan}
 * positional data (top-left normalized coordinates) can use this mapper.
 */
public final class FindingLocationMapper {

    private FindingLocationMapper() {
        // utility
    }

    /**
     * Resolves a bounding box for a finding on the given page.
     *
     * @param page        the extracted page with positional spans
     * @param startOffset finding start offset (inclusive) into the page text
     * @param endOffset   finding end offset (exclusive) into the page text
     * @return the union bounding box, or empty if no spans overlap the range
     */
    public static Optional<FindingLocation.BoundingBox> resolveBoundingBox(
            PageText page, int startOffset, int endOffset) {

        List<TextSpan> spans = page.spans();
        if (spans.isEmpty() || endOffset <= startOffset) {
            return Optional.empty();
        }

        double minLeft = Double.MAX_VALUE;
        double minTop = Double.MAX_VALUE;
        double maxRight = -Double.MAX_VALUE;
        double maxBottom = -Double.MAX_VALUE;
        boolean matched = false;

        for (TextSpan span : spans) {
            // Overlap test between [startOffset, endOffset) and [span.start, span.end)
            if (span.startOffset() < endOffset && span.endOffset() > startOffset) {
                matched = true;
                minLeft = Math.min(minLeft, span.left());
                minTop = Math.min(minTop, span.top());
                maxRight = Math.max(maxRight, span.left() + span.width());
                maxBottom = Math.max(maxBottom, span.top() + span.height());
            }
        }

        if (!matched) {
            return Optional.empty();
        }

        double left = clamp(minLeft);
        double top = clamp(minTop);
        double width = clamp(maxRight - minLeft);
        double height = clamp(maxBottom - minTop);

        // Small padding to fully cover glyph edges, clamped to page bounds.
        double pad = 0.002;
        left = clamp(left - pad);
        top = clamp(top - pad);
        width = clamp(width + 2 * pad);
        height = clamp(height + 2 * pad);
        if (left + width > 1.0) width = 1.0 - left;
        if (top + height > 1.0) height = 1.0 - top;

        if (width <= 0 || height <= 0) {
            return Optional.empty();
        }

        return Optional.of(FindingLocation.BoundingBox.of(left, top, width, height));
    }

    /**
     * Returns the average confidence of positional spans overlapping a finding.
     * Native PDF spans default to 1.0; OCR spans carry provider recognition confidence.
     */
    public static Optional<Double> resolveSpanConfidence(
            PageText page, int startOffset, int endOffset) {
        if (page == null || page.spans().isEmpty() || endOffset <= startOffset) {
            return Optional.empty();
        }
        double total = 0.0;
        int count = 0;
        for (TextSpan span : page.spans()) {
            if (span.startOffset() < endOffset && span.endOffset() > startOffset) {
                total += span.confidence();
                count++;
            }
        }
        return count == 0 ? Optional.empty() : Optional.of(clamp(total / count));
    }

    private static double clamp(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
