package com.securedoc.platform.application.port.out;

import java.io.InputStream;
import java.util.List;

/**
 * Outbound port for extracting text from document content.
 * Implementation is infrastructure-specific (PDFBox for local/demo, cloud OCR later).
 *
 * <p>The application layer depends only on this interface — never on
 * PDFBox, Tika, cloud SDKs, or other framework-specific libraries.
 */
public interface TextExtractionPort {

    /**
     * Extracts text from the given content stream.
     *
     * @param content  the document content (e.g., PDF binary)
     * @param mimeType the MIME type of the content (e.g., "application/pdf")
     * @return extraction result with per-page text
     */
    TextExtractionOutput extract(InputStream content, String mimeType);

    /**
     * Result of a text extraction operation.
     * Contains per-page text and metadata — never stored in events or audit.
     */
    record TextExtractionOutput(
            List<PageText> pages,
            long totalCharacterCount,
            boolean hasEmbeddedText
    ) {
        public TextExtractionOutput {
            if (pages == null) {
                pages = List.of();
            } else {
                pages = List.copyOf(pages);
            }
        }

        public int pageCount() {
            return pages.size();
        }
    }

    /**
     * Text extracted from a single page.
     */
    record PageText(
            int pageNumber,
            int width,
            int height,
            String text
    ) {
        public PageText {
            if (pageNumber < 1) {
                throw new IllegalArgumentException("Page number must be >= 1");
            }
            if (text == null) {
                text = "";
            }
        }
    }
}
