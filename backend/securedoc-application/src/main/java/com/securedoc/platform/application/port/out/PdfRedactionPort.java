package com.securedoc.platform.application.port.out;

import java.io.InputStream;
import java.util.List;

/**
 * Outbound port for performing PDF redaction.
 * Implementations must permanently burn every target so original content is not recoverable
 * on a redacted page. Untouched pages may retain native PDF text/vector content when doing so
 * does not copy unsafe active content.
 *
 * <p>The application layer depends only on this interface — never on
 * PDFBox or other PDF manipulation libraries.
 */
public interface PdfRedactionPort {

    /**
     * Creates a redacted PDF from the original content.
     * The implementation must:
     * <ul>
     *   <li>Burn-flatten every page containing one or more redaction targets.</li>
     *   <li>Apply solid redaction rectangles over all target areas before output.</li>
     *   <li>Never preserve the source text layer or active annotations on a redacted page.</li>
     *   <li>May preserve untouched static pages losslessly to retain searchability and compact size.</li>
     *   <li>Must not copy document-level metadata or attachments into the finished artifact.</li>
     * </ul>
     *
     * @param originalPdf the original PDF content
     * @param targets     the areas to redact (page number + normalized bounding box); may be empty
     *                    when no sensitive findings exist
     * @return replayable redacted PDF content backed by bounded-memory infrastructure
     */
    RedactionOutput redact(InputStream originalPdf, List<RedactionTarget> targets);

    /**
     * Task 0044 (R2 part 1, option B): as {@link #redact(InputStream, List)}, and when {@code options.searchableText()}
     * is on, every burned page also gets an invisible text layer made by OCR of the already-redacted image, so the
     * page is searchable while the boxes cannot leak into the text. Off is exactly {@link #redact(InputStream, List)}.
     * An implementation that cannot add the layer must refuse (fail closed), never silently return image-only pages.
     */
    default RedactionOutput redact(InputStream originalPdf, List<RedactionTarget> targets, RedactionOptions options) {
        if (options != null && options.searchableText()) {
            throw new UnsupportedOperationException("This redaction engine cannot add a searchable text layer");
        }
        return redact(originalPdf, targets);
    }

    /** Output options. {@link #IMAGE_ONLY} is today's output: redacted pages are images with no text. */
    record RedactionOptions(boolean searchableText) {
        public static final RedactionOptions IMAGE_ONLY = new RedactionOptions(false);
        public static final RedactionOptions SEARCHABLE = new RedactionOptions(true);
    }

    /**
     * A single area to redact on a specific page.
     * Coordinates are normalized (0.0-1.0) relative to page dimensions.
     */
    record RedactionTarget(
            int pageNumber,
            double left,
            double top,
            double width,
            double height,
            KeptDigits kept
    ) {
        /** A value removed in full. */
        public RedactionTarget(int pageNumber, double left, double top, double width, double height) {
            this(pageNumber, left, top, width, height, null);
        }

        public RedactionTarget {
            if (pageNumber < 1) {
                throw new IllegalArgumentException("Page number must be >= 1");
            }
            if (left < 0.0 || left > 1.0) {
                throw new IllegalArgumentException("Left must be 0.0-1.0");
            }
            if (top < 0.0 || top > 1.0) {
                throw new IllegalArgumentException("Top must be 0.0-1.0");
            }
            if (width <= 0.0 || width > 1.0) {
                throw new IllegalArgumentException("Width must be > 0.0 and <= 1.0");
            }
            if (height <= 0.0 || height > 1.0) {
                throw new IllegalArgumentException("Height must be > 0.0 and <= 1.0");
            }
            if (left + width > 1.0) {
                throw new IllegalArgumentException("left + width must be <= 1.0");
            }
            if (top + height > 1.0) {
                throw new IllegalArgumentException("top + height must be <= 1.0");
            }
        }

        public static RedactionTarget of(int pageNumber, double left, double top,
                                           double width, double height) {
            return new RedactionTarget(pageNumber, left, top, width, height);
        }

        /** Task 0062: the same area, with the last four digits drawn on the cover. */
        public RedactionTarget keeping(KeptDigits digits) {
            return new RedactionTarget(pageNumber, left, top, width, height, digits);
        }

        public boolean intersects(RedactionTarget other) {
            return pageNumber == other.pageNumber && left < other.left + other.width && other.left < left + width
                    && top < other.top + other.height && other.top < top + height;
        }

        /** The area that is solid in every case: all of it, or the part before the kept digits. */
        public RedactionTarget solidPart() {
            return kept == null ? this
                    : new RedactionTarget(pageNumber, left, top, width * (1 - kept.share()), height);
        }

        /** The area the kept digits are drawn in (the end of the cover); only with {@code kept}. */
        public RedactionTarget keptPart() {
            double start = left + width * (1 - kept.share());
            return new RedactionTarget(pageNumber, start, top, Math.min(width * kept.share(), 1.0 - start), height);
        }
    }

    /**
     * Task 0062, style "keep the last 4": the four digits, read from the original for one redaction and held in memory
     * only. Never saved or logged: {@link #toString()} shows none of them, and equality is by identity.
     */
    final class KeptDigits {
        private final String digits;
        private final double share;

        /** @param share the part of the cover the digits take, at its end (more than 0, at most a half) */
        public KeptDigits(String digits, double share) {
            if (digits == null || !digits.matches("[0-9]{4}")) {
                throw new IllegalArgumentException("Exactly four digits are kept");
            }
            if (!(share > 0.0 && share <= 0.5)) {
                throw new IllegalArgumentException("The kept part is at most half of the cover");
            }
            this.digits = digits;
            this.share = share;
        }

        /** For drawing them on the cover, nothing else. */
        public String digitsToDraw() {
            return digits;
        }

        public double share() {
            return share;
        }

        @Override
        public String toString() {
            return "KeptDigits[****]";
        }
    }

    /**
     * Result of the redaction operation. Task 0050: {@code signatureRemoved} says the input was digitally signed; the
     * output is not.
     */
    record RedactionOutput(ReplayableContent redactedPdfContent, int pagesRendered, int redactionsApplied,
                           boolean signatureRemoved) {
        /** An input without a digital signature. */
        public RedactionOutput(ReplayableContent redactedPdfContent, int pagesRendered, int redactionsApplied) {
            this(redactedPdfContent, pagesRendered, redactionsApplied, false);
        }

        public RedactionOutput {
            if (redactedPdfContent == null || redactedPdfContent.contentLength() <= 0) {
                throw new IllegalArgumentException("Redacted PDF content must not be empty");
            }
        }
    }
}
