package com.securedoc.platform.infrastructure.routing;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfBoxDerivedPdfBuilderAdapterTest {
    @Test
    void buildsDistinctPagesInOriginalSourceOrder() throws Exception {
        byte[] source = pdfWithPages("PAGE-1", "PAGE-2", "PAGE-3");
        byte[] derived = new PdfBoxDerivedPdfBuilderAdapter().build(new ByteArrayInputStream(source), List.of(3, 1, 3));

        try (PDDocument document = Loader.loadPDF(derived)) {
            assertEquals(2, document.getNumberOfPages());
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.indexOf("PAGE-1") >= 0);
            assertTrue(text.indexOf("PAGE-3") > text.indexOf("PAGE-1"));
            assertFalse(text.contains("PAGE-2"));
        }
    }

    @Test
    void rejectsPageOutsideRedactedMaster() throws Exception {
        byte[] source = pdfWithPages("ONLY");
        assertThrows(IllegalArgumentException.class,
                () -> new PdfBoxDerivedPdfBuilderAdapter().build(new ByteArrayInputStream(source), List.of(2)));
    }

    private byte[] pdfWithPages(String... values) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String value : values) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(font, 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(value);
                    content.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
