package com.securedoc.platform.infrastructure.extraction;

import com.securedoc.platform.application.port.out.TextExtractionPort;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.securedoc.platform.infrastructure.pdf.PdfBoxTempFileSupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Local/demo PDF text extraction using Apache PDFBox.
 * Extracts embedded text from digital PDFs, including per-glyph positional data
 * so detected character ranges can later be mapped to page coordinates automatically.
 *
 * <p>Limitations:
 * <ul>
 *   <li>Only extracts embedded/digital text (not scanned images)</li>
 *   <li>If a PDF has no embedded text, returns empty result with hasEmbeddedText=false</li>
 *   <li>Cloud OCR for scanned documents is deferred to a future unit</li>
 * </ul>
 *
 * <p>PDFBox types are NOT exposed outside this class.
 * Extracted text is NOT written to logs.
 */
@Component
public class PdfBoxTextExtractionAdapter implements TextExtractionPort {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxTextExtractionAdapter.class);

    @Override
    public TextExtractionOutput extract(InputStream content, String mimeType) {
        if (!"application/pdf".equals(mimeType)) {
            log.warn("Unsupported MIME type for text extraction: {}. Only application/pdf is supported.", mimeType);
            return new TextExtractionOutput(List.of(), 0, false);
        }

        try (PdfBoxTempFileSupport.StagedPdf staged = PdfBoxTempFileSupport.stage(content, "lexavault-native-text-")) {
            return extractDocument(staged.document());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract text from PDF", e);
        }
    }

    /** Package-private reuse for the hybrid adapter so a large PDF is staged only once. */
    TextExtractionOutput extractDocument(PDDocument document) throws IOException {
        int pageCount = document.getNumberOfPages();

        if (pageCount == 0) {
            return new TextExtractionOutput(List.of(), 0, false);
        }

        List<PageText> pages = new ArrayList<>(pageCount);
        long totalChars = 0;

        for (int i = 1; i <= pageCount; i++) {
            PDPage pdPage = document.getPage(i - 1);
            PDRectangle mediaBox = pdPage.getMediaBox();
            float pageWidth = mediaBox.getWidth();
            float pageHeight = mediaBox.getHeight();

            PositionCapturingStripper stripper = new PositionCapturingStripper(pageWidth, pageHeight);
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            stripper.getText(document);

            String pageText = stripper.getCapturedText();
            pages.add(new PageText(
                    i,
                    Math.round(pageWidth),
                    Math.round(pageHeight),
                    pageText,
                    stripper.getSpans()));
            totalChars += pageText.length();
        }

        boolean hasEmbeddedText = totalChars > 0;
        if (!hasEmbeddedText) {
            log.info("PDF has no extractable embedded text ({} pages). OCR provider required for scanned documents.", pageCount);
        }
        return new TextExtractionOutput(pages, totalChars, hasEmbeddedText);
    }

    /**
     * PDFTextStripper subclass that records per-glyph-run positional data alongside
     * the character offsets in the emitted page text. Coordinates are normalized to
     * a top-left origin (matching the redaction adapter's coordinate space).
     */
    private static final class PositionCapturingStripper extends PDFTextStripper {

        private final float pageWidth;
        private final float pageHeight;
        private final List<TextSpan> spans = new ArrayList<>();
        private final StringBuilder captured = new StringBuilder();

        PositionCapturingStripper(float pageWidth, float pageHeight) throws IOException {
            this.pageWidth = pageWidth;
            this.pageHeight = pageHeight;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            for (TextPosition tp : textPositions) {
                int charStart = captured.length();
                String unicode = tp.getUnicode();
                if (unicode == null) {
                    unicode = "";
                }
                captured.append(unicode);
                int charEnd = captured.length();

                if (unicode.isBlank() || pageWidth <= 0 || pageHeight <= 0) {
                    continue;
                }

                // TextPosition getXDirAdj/getYDirAdj use a top-left origin.
                float x = tp.getXDirAdj();
                float y = tp.getYDirAdj();
                float w = tp.getWidthDirAdj();
                float h = tp.getHeightDir();

                double left = clamp(x / pageWidth);
                double top = clamp((y - h) / pageHeight);
                double width = clamp(w / pageWidth);
                double height = clamp(h / pageHeight);

                if (width <= 0 || height <= 0) {
                    continue;
                }
                if (left + width > 1.0) width = 1.0 - left;
                if (top + height > 1.0) height = 1.0 - top;
                if (width <= 0 || height <= 0) {
                    continue;
                }

                spans.add(new TextSpan(charStart, charEnd, left, top, width, height));
            }
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            captured.append('\n');
        }

        @Override
        protected void writeWordSeparator() throws IOException {
            captured.append(' ');
        }

        String getCapturedText() {
            return captured.toString();
        }

        List<TextSpan> getSpans() {
            return spans;
        }

        private static double clamp(double v) {
            if (v < 0.0) return 0.0;
            if (v > 1.0) return 1.0;
            return v;
        }
    }
}
