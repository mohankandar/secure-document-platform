package com.securedoc.platform.infrastructure.redaction;

import com.securedoc.platform.application.port.out.ReplayableContent;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionOptions;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionOutput;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionTarget;
import com.securedoc.platform.application.port.out.RedactionVerificationPort.VerificationResult;
import com.securedoc.platform.application.port.out.RedactionVerificationPort.RedactedValues;
import com.securedoc.platform.infrastructure.routing.PdfBoxDerivedPdfBuilderAdapter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0057: the mark of an output made under a licence for tests, and only of that. Synthetic values only. */
class NonProductionMarkTest {

    private static final String SSN = "219-09-9999";
    /** Two boxes over the middle of page 1 (they overlap each other and the diagonal) and one in a corner. */
    private static final List<RedactionTarget> TARGETS = List.of(RedactionTarget.of(1, 0.3, 0.45, 0.4, 0.06),
            RedactionTarget.of(1, 0.4, 0.48, 0.2, 0.1), RedactionTarget.of(1, 0.02, 0.9, 0.2, 0.06));
    private static final RedactionOptions MARKED = new RedactionOptions(false, true);

    private final PdfBoxBurnFlatRedactionAdapter engine = new PdfBoxBurnFlatRedactionAdapter();
    private final PdfBoxRedactionVerificationAdapter verification = new PdfBoxRedactionVerificationAdapter();

    {
        engine.clock = Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void withoutTheMarkTheOutputIsTodaysByteForByte() throws Exception {
        byte[] today = bytes(engine.redact(new ByteArrayInputStream(input()), TARGETS));
        assertArrayEquals(withoutDocumentId(today), withoutDocumentId(redact(new RedactionOptions(false, false))));
        assertArrayEquals(withoutDocumentId(today), withoutDocumentId(redact(RedactionOptions.IMAGE_ONLY)));
        try (PDDocument doc = Loader.loadPDF(today)) {
            assertFalse(doc.getDocumentInformation().getCOSObject().containsKey(NonProductionMark.FLAG));
        }
        assertFalse(new String(withoutDocumentId(redact(MARKED)), StandardCharsets.ISO_8859_1)
                .equals(new String(withoutDocumentId(today), StandardCharsets.ISO_8859_1)), "the mark changes the file");
    }

    @Test
    void theMarkIsOnEveryPageAndNeverOnARedaction() throws Exception {
        byte[] plain = redact(RedactionOptions.IMAGE_ONLY);
        byte[] marked = redact(MARKED);
        try (PDDocument a = Loader.loadPDF(plain); PDDocument b = Loader.loadPDF(marked)) {
            assertEquals(a.getNumberOfPages(), b.getNumberOfPages());
            for (int page = 0; page < b.getNumberOfPages(); page++) {
                BufferedImage without = new PDFRenderer(a).renderImageWithDPI(page, 100);
                BufferedImage with = new PDFRenderer(b).renderImageWithDPI(page, 100);
                Rectangle drawn = difference(without, with);
                assertTrue(drawn.width > with.getWidth() * 0.5 && drawn.height > with.getHeight() * 0.5,
                        "page " + (page + 1) + ": across the page, " + drawn);
                assertTrue(drawn.x > 0 && drawn.y > 0 && drawn.x + drawn.width < with.getWidth()
                        && drawn.y + drawn.height < with.getHeight(), "page " + (page + 1) + ": whole on the page");
            }
            BufferedImage without = new PDFRenderer(a).renderImageWithDPI(0, 200);
            BufferedImage with = new PDFRenderer(b).renderImageWithDPI(0, 200);
            for (RedactionTarget t : TARGETS) {
                int x0 = (int) Math.floor(t.left() * with.getWidth());
                int y0 = (int) Math.floor(t.top() * with.getHeight());
                for (int y = y0; y < (t.top() + t.height()) * with.getHeight(); y++) {
                    for (int x = x0; x < (t.left() + t.width()) * with.getWidth(); x++) {
                        assertEquals(without.getRGB(x, y), with.getRGB(x, y), "box pixel " + x + "," + y);
                    }
                }
            }
        }
        proof(marked);
    }

    @Test
    void itAddsNoTextNoFontNothingHiddenAndTheFinalCheckReadsTheMarkedFile() throws Exception {
        byte[] plain = redact(RedactionOptions.IMAGE_ONLY);
        byte[] marked = redact(MARKED);
        try (PDDocument a = Loader.loadPDF(plain); PDDocument b = Loader.loadPDF(marked)) {
            assertEquals(new PDFTextStripper().getText(a), new PDFTextStripper().getText(b));
            for (int page = 0; page < a.getNumberOfPages(); page++) {
                assertEquals(names(a.getPage(page)), names(b.getPage(page)));
                assertTrue(b.getPage(page).getAnnotations().isEmpty());
            }
            assertTrue(b.getDocumentInformation().getCOSObject().getBoolean(NonProductionMark.FLAG, false));
            assertEquals("LexaVault", b.getDocumentInformation().getProducer());
        }
        assertFalse(new String(marked, StandardCharsets.ISO_8859_1).contains("PRODUCTION USE"));
        VerificationResult safe = verify(marked, RedactedValues.of(List.of(SSN)));
        assertTrue(safe.safe(), () -> "" + safe.failureCode());
        assertEquals(TARGETS.size(), safe.coveredRedactionCount());
        // The check still refuses a marked file that holds a redacted value.
        try (PDDocument leaked = Loader.loadPDF(marked); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            leaked.getDocumentInformation().setTitle(SSN);
            leaked.save(out);
            assertFalse(verify(out.toByteArray(), RedactedValues.of(List.of(SSN))).safe());
        }
    }

    @Test
    void twoServersDrawTheSame() throws Exception {
        PdfBoxBurnFlatRedactionAdapter other = new PdfBoxBurnFlatRedactionAdapter();
        other.clock = engine.clock;
        assertArrayEquals(withoutDocumentId(redact(MARKED)), withoutDocumentId(bytes(other.redact(
                new ByteArrayInputStream(input()), TARGETS, MARKED))));
    }

    @Test
    void aFileMadeFromMarkedPagesIsMarkedAndOneFromOthersIsNot() throws Exception {
        for (boolean mark : new boolean[] {true, false}) {
            try (ReplayableContent derived = new PdfBoxDerivedPdfBuilderAdapter().build(new ByteArrayInputStream(
                    redact(new RedactionOptions(false, mark))), List.of(2)); InputStream in = derived.openStream();
                 PDDocument doc = Loader.loadPDF(in.readAllBytes())) {
                assertEquals(mark, doc.getDocumentInformation().getCOSObject().getBoolean(NonProductionMark.FLAG, false));
            }
        }
    }

    private byte[] redact(RedactionOptions options) throws IOException {
        return bytes(engine.redact(new ByteArrayInputStream(input()), TARGETS, options));
    }

    private VerificationResult verify(byte[] output, RedactedValues values) {
        return verification.verify(new ByteArrayInputStream(output), 5, TARGETS, false, values);
    }

    /**
     * Page 1 is redacted; 2 is an ordinary copied page; 3 is turned; 4 has a crop box and a scale left open by its own
     * content; 5 is blank.
     */
    private static byte[] input() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            page(doc, "Loan file " + SSN, "");
            page(doc, "SECOND PAGE STAYS TEXT", "");
            page(doc, "TURNED PAGE", "").setRotation(90);
            PDPage open = page(doc, "OPEN STATES", "q 0.1 0 0 0.1 300 300 cm q\n");
            open.setCropBox(new PDRectangle(50, 60, 400, 500));
            doc.addPage(new PDPage(PDRectangle.LETTER));
            doc.save(out);
            return out.toByteArray();
        }
    }

    @SuppressWarnings("deprecation")
    private static PDPage page(PDDocument doc, String text, String raw) throws IOException {
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
            cs.newLineAtOffset(100, 500);
            cs.showText(text);
            cs.endText();
            if (!raw.isEmpty()) cs.appendRawCommands(raw);
        }
        return page;
    }

    private static List<String> names(PDPage page) {
        List<String> out = new java.util.ArrayList<>();
        if (page.getResources() != null) page.getResources().getFontNames().forEach(n -> out.add(n.getName()));
        return out;
    }

    private static Rectangle difference(BufferedImage a, BufferedImage b) {
        Rectangle all = new Rectangle(-1, -1);
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) all.add(new Rectangle(x, y, 1, 1));
            }
        }
        return all;
    }

    private static byte[] withoutDocumentId(byte[] pdf) {
        return new String(pdf, StandardCharsets.ISO_8859_1).replaceAll("/ID \\[<[0-9A-F]+> <[0-9A-F]+>\\]", "/ID")
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] bytes(RedactionOutput output) throws IOException {
        try (ReplayableContent content = output.redactedPdfContent(); InputStream in = content.openStream()) {
            return in.readAllBytes();
        }
    }

    /** With -Dnonproduction.mark.proof=<folder> the marked pages are saved as pictures. */
    private static void proof(byte[] marked) throws IOException {
        String folder = System.getProperty("nonproduction.mark.proof");
        if (folder == null || folder.isBlank()) return;
        Files.createDirectories(Path.of(folder));
        try (PDDocument doc = Loader.loadPDF(marked)) {
            for (int page = 0; page < 4; page++) {
                javax.imageio.ImageIO.write(new PDFRenderer(doc).renderImageWithDPI(page, 60), "png",
                        Path.of(folder, "marked-page-" + (page + 1) + ".png").toFile());
            }
        }
    }
}
