package com.securedoc.platform.infrastructure.extraction;

import com.securedoc.platform.application.port.out.OcrPort;
import com.securedoc.platform.application.port.out.TextExtractionPort.PageText;
import com.securedoc.platform.application.port.out.TextExtractionPort.TextExtractionOutput;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Task 0047, item 2: the redaction step's second read of the original asks only for the pages with findings. Each page
 * it returns is exactly the page detection read (same text, so the same offsets, and the same spans), and pages that
 * were not asked for are not OCR'd again. Synthetic text only.
 */
class HybridPdfExtractPagesTest {

    @Test
    void onlyTheAskedPagesAreReadAndOcrdAndEachReadsAsInTheFullExtraction() throws Exception {
        List<Integer> ocrCalls = new ArrayList<>();
        HybridPdfTextExtractionAdapter adapter = adapter(ocrCalls);
        byte[] pdf = fourPages(); // 1 typed, 2 scanned, 3 typed, 4 scanned

        TextExtractionOutput full = adapter.extract(new ByteArrayInputStream(pdf), "application/pdf");
        assertEquals(List.of(2, 4), ocrCalls, "the full read OCRs both scanned pages");
        ocrCalls.clear();

        TextExtractionOutput some = adapter.extractPages(new ByteArrayInputStream(pdf), "application/pdf", Set.of(3, 2, 99));
        assertEquals(List.of(2), ocrCalls, "only the asked scanned page is OCR'd");
        assertEquals(List.of(2, 3), some.pages().stream().map(PageText::pageNumber).toList(), "in page order; 99 ignored");
        for (PageText page : some.pages()) {
            PageText same = full.pages().get(page.pageNumber() - 1);
            assertEquals(same.text(), page.text(), "page " + page.pageNumber());
            assertEquals(same.spans(), page.spans(), "page " + page.pageNumber());
            assertEquals(same.source(), page.source(), "page " + page.pageNumber());
        }

        ocrCalls.clear();
        assertEquals(0, adapter.extractPages(new ByteArrayInputStream(pdf), "application/pdf", Set.of()).pageCount());
        assertEquals(List.of(), ocrCalls);
    }

    @Test
    void theNativeAdapterReadsOnlyTheAskedPagesTheSameWay() throws Exception {
        PdfBoxTextExtractionAdapter adapter = new PdfBoxTextExtractionAdapter();
        byte[] pdf = fourPages();
        TextExtractionOutput full = adapter.extract(new ByteArrayInputStream(pdf), "application/pdf");
        TextExtractionOutput some = adapter.extractPages(new ByteArrayInputStream(pdf), "application/pdf", Set.of(3, 1));
        assertEquals(List.of(1, 3), some.pages().stream().map(PageText::pageNumber).toList());
        for (PageText page : some.pages()) {
            assertEquals(full.pages().get(page.pageNumber() - 1).text(), page.text());
            assertEquals(full.pages().get(page.pageNumber() - 1).spans(), page.spans());
        }
    }

    private static HybridPdfTextExtractionAdapter adapter(List<Integer> ocrCalls) {
        TesseractOcrProperties properties = new TesseractOcrProperties();
        properties.setMinNativeCharacters(16);
        properties.setMinimumPageConfidence(0.55);
        OcrPort ocr = input -> {
            ocrCalls.add(input.pageNumber());
            String text = "Scanned page " + input.pageNumber() + " Applicant reference 000-00-000" + input.pageNumber();
            return new OcrPort.OcrPageOutput(text,
                    List.of(new OcrPort.OcrTextSpan(0, text.length(), 0.1, 0.2, 0.6, 0.05, 0.99)), 0.99);
        };
        return new HybridPdfTextExtractionAdapter(new PdfBoxTextExtractionAdapter(), ocr, properties);
    }

    private static byte[] fourPages() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int n = 1; n <= 4; n++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    if (n % 2 == 1) {
                        content.beginText();
                        content.setFont(font, 12);
                        content.newLineAtOffset(54, 700);
                        content.showText("Typed page " + n + " loan application reviewed by the borrower");
                        content.newLineAtOffset(0, -20);
                        content.showText("Reference number 000-00-000" + n);
                        content.endText();
                    } else {
                        BufferedImage scan = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = scan.createGraphics();
                        try {
                            g.setColor(Color.WHITE);
                            g.fillRect(0, 0, 600, 600);
                            g.setColor(Color.BLACK);
                            g.fillRect(60, 80, 480, 300);
                        } finally {
                            g.dispose();
                        }
                        PDImageXObject image = LosslessFactory.createFromImage(document, scan);
                        content.drawImage(image, 72, 150, 468, 500);
                    }
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        }
    }
}
