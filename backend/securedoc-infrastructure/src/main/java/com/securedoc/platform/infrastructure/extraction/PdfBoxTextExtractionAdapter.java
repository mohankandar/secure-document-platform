package com.securedoc.platform.infrastructure.extraction;

import com.securedoc.platform.application.port.out.TextExtractionPort;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Local/demo PDF text extraction using Apache PDFBox.
 * Extracts embedded text from digital PDFs.
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

        try (PDDocument document = Loader.loadPDF(content.readAllBytes())) {
            int pageCount = document.getNumberOfPages();

            if (pageCount == 0) {
                return new TextExtractionOutput(List.of(), 0, false);
            }

            List<PageText> pages = new ArrayList<>(pageCount);
            long totalChars = 0;
            PDFTextStripper stripper = new PDFTextStripper();

            for (int i = 1; i <= pageCount; i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);

                String pageText = stripper.getText(document);
                if (pageText == null) {
                    pageText = "";
                } else {
                    pageText = pageText.strip();
                }

                // Get page dimensions
                PDPage pdPage = document.getPage(i - 1);
                PDRectangle mediaBox = pdPage.getMediaBox();
                int width = Math.round(mediaBox.getWidth());
                int height = Math.round(mediaBox.getHeight());

                pages.add(new PageText(i, width, height, pageText));
                totalChars += pageText.length();
            }

            boolean hasEmbeddedText = totalChars > 0;

            if (!hasEmbeddedText) {
                log.info("PDF has no extractable embedded text ({} pages). " +
                        "OCR provider required for scanned documents.", pageCount);
            }

            return new TextExtractionOutput(pages, totalChars, hasEmbeddedText);

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract text from PDF", e);
        }
    }
}
