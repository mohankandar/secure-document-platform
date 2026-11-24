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
            double height
    ) {
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
