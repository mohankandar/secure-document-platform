package com.securedoc.platform.application.port.out;

import java.io.InputStream;
import java.util.List;

/**
 * Outbound port for extracting normalized page text and trusted page coordinates.
 * Implementations may use native PDF text, OCR, or a hybrid of both.
 *
 * <p>The application layer depends only on this interface — never on PDFBox,
 * Tesseract, cloud OCR SDKs, or other framework-specific libraries.
 */
public interface TextExtractionPort {

    /**
     * Extracts normalized text from the given content stream.
     *
     * @param content  the document content (e.g., PDF binary)
     * @param mimeType the MIME type of the content (e.g., "application/pdf")
     * @return extraction result with per-page text and positions
     */
    TextExtractionOutput extract(InputStream content, String mimeType);

    /** Source used to normalize a page into text + coordinates. */
    enum ExtractionSource {
        NATIVE_PDF_TEXT,
        OCR
    }

    /**
     * Result of a text extraction operation.
     * Contains per-page text and safe metadata — never stored in events or audit.
     */
    record TextExtractionOutput(
            List<PageText> pages,
            long totalCharacterCount,
            boolean hasEmbeddedText,
            boolean usedOcr
    ) {
        public TextExtractionOutput {
            if (pages == null) {
                pages = List.of();
            } else {
                pages = List.copyOf(pages);
            }
        }

        /** Compatibility constructor for native-only implementations. */
        public TextExtractionOutput(List<PageText> pages,
                                    long totalCharacterCount,
                                    boolean hasEmbeddedText) {
            this(pages, totalCharacterCount, hasEmbeddedText, false);
        }

        public int pageCount() {
            return pages.size();
        }

        /** True when native extraction and/or OCR produced usable text. */
        public boolean hasUsableText() {
            return totalCharacterCount > 0 && pages.stream().anyMatch(page -> !page.text().isBlank());
        }

        public long ocrPageCount() {
            return pages.stream().filter(page -> page.source() == ExtractionSource.OCR).count();
        }

        public long nativePageCount() {
            return pages.stream().filter(page -> page.source() == ExtractionSource.NATIVE_PDF_TEXT).count();
        }
    }

    /**
     * Text normalized from a single page.
     *
     * <p>{@code spans} carry positional data used to map detected character ranges
     * back to normalized page coordinates. Native PDF extraction and OCR populate
     * the same model so downstream AI/detection/redaction does not care where the
     * text came from.
     */
    record PageText(
            int pageNumber,
            int width,
            int height,
            String text,
            List<TextSpan> spans,
            ExtractionSource source,
            double sourceConfidence
    ) {
        public PageText {
            if (pageNumber < 1) {
                throw new IllegalArgumentException("Page number must be >= 1");
            }
            if (text == null) {
                text = "";
            }
            if (spans == null) {
                spans = List.of();
            } else {
                spans = List.copyOf(spans);
            }
            if (source == null) {
                source = ExtractionSource.NATIVE_PDF_TEXT;
            }
            if (Double.isNaN(sourceConfidence) || sourceConfidence < 0.0 || sourceConfidence > 1.0) {
                throw new IllegalArgumentException("Source confidence must be between 0.0 and 1.0");
            }
        }

        public PageText(int pageNumber, int width, int height, String text, List<TextSpan> spans) {
            this(pageNumber, width, height, text, spans, ExtractionSource.NATIVE_PDF_TEXT, 1.0);
        }

        public PageText(int pageNumber, int width, int height, String text) {
            this(pageNumber, width, height, text, List.of());
        }
    }

    /**
     * Positional data for a run of characters within a page.
     * Character range is [startOffset, endOffset) into the page text.
     * Coordinates are normalized (0.0-1.0) with a top-left origin, matching the
     * coordinate space used by the redaction adapter.
     */
    record TextSpan(
            int startOffset,
            int endOffset,
            double left,
            double top,
            double width,
            double height,
            double confidence
    ) {
        public TextSpan {
            if (startOffset < 0 || endOffset < startOffset) {
                throw new IllegalArgumentException("Invalid span offsets");
            }
            if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("Span confidence must be between 0.0 and 1.0");
            }
        }

        /** Compatibility constructor for trusted native PDF spans. */
        public TextSpan(int startOffset, int endOffset,
                        double left, double top, double width, double height) {
            this(startOffset, endOffset, left, top, width, height, 1.0);
        }
    }
}
