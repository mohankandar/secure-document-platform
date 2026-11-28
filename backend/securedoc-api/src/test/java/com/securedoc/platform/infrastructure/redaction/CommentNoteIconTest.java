package com.securedoc.platform.infrastructure.redaction;

import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionOutput;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionTarget;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0111 (owner's test R6): a comment note is removed from the output, so its icon is not drawn into the page either,
 * on a copied page and on a page drawn as an image.
 */
class CommentNoteIconTest {
    private final PdfBoxBurnFlatRedactionAdapter redaction = new PdfBoxBurnFlatRedactionAdapter();

    @Test
    void aCommentNoteIconIsNotDrawnOnACopiedPage() throws Exception {
        assertNoIcon(redaction.redact(new ByteArrayInputStream(pdfWithNote()), List.of()));
    }

    @Test
    void aCommentNoteIconIsNotDrawnOnAPageDrawnAsAnImage() throws Exception {
        RedactionTarget elsewhere = RedactionTarget.of(1, 0.10, 0.80, 0.20, 0.03);
        assertNoIcon(redaction.redact(new ByteArrayInputStream(pdfWithNote()), List.of(elsewhere)));
    }

    @Test
    void theNoteIconIsDrawnWhenNothingRemovesIt() throws Exception {
        // The test's own check: rendering the input shows the icon, so the two tests above can fail.
        try (PDDocument doc = Loader.loadPDF(pdfWithNote())) {
            assertTrue(darkPixels(new PDFRenderer(doc).renderImage(0)) > 0, "the input shows the note's icon");
        }
    }

    private static void assertNoIcon(RedactionOutput result) throws Exception {
        byte[] output;
        try (var content = result.redactedPdfContent(); InputStream in = content.openStream()) {
            output = in.readAllBytes();
        }
        try (PDDocument doc = Loader.loadPDF(output)) {
            assertTrue(darkPixels(new PDFRenderer(doc).renderImage(0)) == 0, "the note's icon is drawn in the output");
        }
    }

    /** Pixels in the note's place (PDF 300..320 x 640..660 at 72 dpi) that are not white. */
    private static int darkPixels(BufferedImage image) {
        int dark = 0;
        int scaleX = image.getWidth() / 612;
        int s = Math.max(1, scaleX);
        for (int y = (792 - 662) * s; y < (792 - 638) * s; y++) {
            for (int x = 298 * s; x < 322 * s; x++) {
                int rgb = image.getRGB(x, y);
                int min = Math.min((rgb >> 16) & 0xff, Math.min((rgb >> 8) & 0xff, rgb & 0xff));
                if (min < 200) dark++;
            }
        }
        return dark;
    }

    private static byte[] pdfWithNote() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("VISIBLE PAGE");
                cs.endText();
            }
            PDAnnotationText note = new PDAnnotationText();
            note.setContents("A comment");
            note.setRectangle(new PDRectangle(300, 640, 20, 20));
            note.constructAppearances(doc);
            page.getAnnotations().add(note);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
