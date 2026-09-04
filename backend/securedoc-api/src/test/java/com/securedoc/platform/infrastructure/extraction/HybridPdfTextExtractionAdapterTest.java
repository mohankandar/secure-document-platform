package com.securedoc.platform.infrastructure.extraction;

import com.securedoc.platform.application.port.out.OcrPort;
import com.securedoc.platform.application.port.out.TextExtractionPort.ExtractionSource;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridPdfTextExtractionAdapterTest {

    @Test
    void sparseNativeWrapperAroundDenseRasterPageStillUsesOcr() throws Exception {
        TesseractOcrProperties properties = new TesseractOcrProperties();
        properties.setMinNativeCharacters(32);
        properties.setSparseNativeImageCheckMaxCharacters(400);
        properties.setSparseNativeImageCheckDpi(72);
        properties.setSparseNativeImageDarkPixelRatio(0.01);
        properties.setMinimumPageConfidence(0.55);

        String ocrText = "Social Security Number: 987-65-4323\n"
                + "Date of Birth: 02/21/1974\n"
                + "Credit Card Number: 6011 1111 1111 1117\n"
                + "Credit Score: 768";
        OcrPort ocr = input -> new OcrPort.OcrPageOutput(
                ocrText,
                List.of(new OcrPort.OcrTextSpan(
                        0, ocrText.length(), 0.15, 0.20, 0.70, 0.45, 0.99)),
                0.99);

        HybridPdfTextExtractionAdapter adapter = new HybridPdfTextExtractionAdapter(
                new PdfBoxTextExtractionAdapter(), ocr, properties);

        var output = adapter.extract(
                new ByteArrayInputStream(buildMixedNativeAndRasterPdf()), "application/pdf");

        assertEquals(1, output.pages().size());
        assertTrue(output.usedOcr());
        assertEquals(ExtractionSource.OCR, output.pages().get(0).source());
        assertEquals(ocrText, output.pages().get(0).text());
    }

    private byte[] buildMixedNativeAndRasterPdf() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);

            BufferedImage scan = new BufferedImage(900, 900, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scan.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, scan.getWidth(), scan.getHeight());
                graphics.setColor(Color.BLACK);
                // Dense raster payload standing in for a scanned identity form.
                graphics.fillRect(100, 120, 700, 520);
            } finally {
                graphics.dispose();
            }

            PDImageXObject image = LosslessFactory.createFromImage(document, scan);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, 12);
                content.newLineAtOffset(54, 740);
                content.showText("Scanned Identity Verification Appendix - native wrapper text");
                content.endText();

                content.drawImage(image, 72, 150, 468, 500);

                content.beginText();
                content.setFont(font, 9);
                content.newLineAtOffset(54, 36);
                content.showText("Synthetic precision fixture - page footer");
                content.endText();
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        }
    }
}
