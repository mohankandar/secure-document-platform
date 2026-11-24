package com.securedoc.platform.infrastructure.redaction;

import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionOptions;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionOutput;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionTarget;
import com.securedoc.platform.application.port.out.RedactionVerificationPort.RedactedValues;
import com.securedoc.platform.application.port.out.RedactionVerificationPort.VerificationResult;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrAdapter;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0044 (R2 part 1, option B): a redacted page keeps its burned image and gets an invisible text layer made by OCR
 * of the redacted image. Fake values only. The redacted SSN, card number and date of birth never appear in the output
 * text, in copied text or anywhere in the PDF bytes (raw or decompressed); the other words on the page are searchable;
 * off is today's output byte for byte; a planted leak makes the final check fail closed. Needs Tesseract on PATH (as
 * the product does; CI installs it).
 */
class SearchableRedactedPagesTest {

    private static final String SSN = "412-55-7531";
    private static final String CARD = "4000 0566 5566 5556";
    private static final String DOB = "03/14/1962";
    private static final float W = 612, H = 792, X = 72, SIZE = 14;
    private static final PDType1Font FONT = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    /** label, value, baseline y */
    private static final String[][] LINES = {
            {"Loan application for a test borrower", null},
            {"Borrower SSN: ", SSN},
            {"Card number: ", CARD},
            {"Date of birth: ", DOB},
            {"Monthly income is reviewed every year", null},
    };

    private static byte[] source;
    private static List<RedactionTarget> targets;

    private final PdfBoxBurnFlatRedactionAdapter searchableEngine =
            new PdfBoxBurnFlatRedactionAdapter(new TesseractOcrAdapter(new TesseractOcrProperties()));
    private final PdfBoxBurnFlatRedactionAdapter todaysEngine = new PdfBoxBurnFlatRedactionAdapter();
    private final PdfBoxRedactionVerificationAdapter verification = new PdfBoxRedactionVerificationAdapter();

    @BeforeAll
    static void makeSource() throws Exception {
        targets = new ArrayList<>();
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(new PDRectangle(W, H));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 700;
                for (String[] line : LINES) {
                    String all = line[1] == null ? line[0] : line[0] + line[1];
                    cs.beginText();
                    cs.setFont(FONT, SIZE);
                    cs.newLineAtOffset(X, y);
                    cs.showText(all);
                    cs.endText();
                    if (line[1] != null) {
                        float start = X + width(line[0]);
                        float end = start + width(line[1]);
                        // The box a detection gives: the value's span with a little padding.
                        float top = y + SIZE * 0.95f + 2, bottom = y - SIZE * 0.25f - 2;
                        targets.add(RedactionTarget.of(1, (start - 2) / W, (H - top) / H, (end - start + 4) / W,
                                (top - bottom) / H));
                    }
                    y -= 40;
                }
            }
            PDPage untouched = new PDPage(new PDRectangle(W, H));
            doc.addPage(untouched);
            try (PDPageContentStream cs = new PDPageContentStream(doc, untouched)) {
                cs.beginText();
                cs.setFont(FONT, SIZE);
                cs.newLineAtOffset(X, 700);
                cs.showText("KEEP-TWO untouched page text");
                cs.endText();
            }
            doc.save(out);
            source = out.toByteArray();
        }
    }

    private static float width(String s) throws Exception {
        return FONT.getStringWidth(s) / 1000f * SIZE;
    }

    @Test
    void theTextLayerHoldsTheOtherWordsButNoRedactedValue() throws Exception {
        byte[] output = redact(searchableEngine, RedactionOptions.SEARCHABLE);
        try (PDDocument doc = Loader.loadPDF(output)) {
            String page1 = text(doc, 1).toLowerCase(Locale.ROOT);
            for (String word : List.of("loan", "application", "borrower", "income", "reviewed")) {
                assertTrue(page1.contains(word), "the redacted page is searchable: '" + word + "' is in its text");
            }
            assertTrue(text(doc, 2).contains("KEEP-TWO"), "the untouched page keeps its own text");
            assertNoValue("text of the output", text(doc, 1) + text(doc, 2));
            // What a reader gets when copying everything: the same extracted text, as one string.
            assertNoValue("copied text", new PDFTextStripper().getText(doc));
            assertNoValue("decompressed page content", decompressedContent(doc));
        }
        assertNoValue("raw PDF bytes", new String(output, StandardCharsets.ISO_8859_1));
        VerificationResult result = verifyOutput(output, true);
        assertTrue(result.safe(), () -> "final check: " + result.failureCode());
    }

    @Test
    void theLayerIsInvisibleTextOverTheImage() throws Exception {
        byte[] output = redact(searchableEngine, RedactionOptions.SEARCHABLE);
        String content;
        try (PDDocument doc = Loader.loadPDF(output)) {
            content = decompressedContent(doc);
        }
        assertTrue(content.contains("3 Tr"), "rendering mode 3 (neither fill nor stroke): the text is not drawn");
        assertTrue(content.contains("Do"), "the burned page image is still drawn");
    }

    @Test
    void offIsTodaysOutputByteForByte() throws Exception {
        // Today's engine already differs between two runs in exactly one place: the trailer's /ID, a new random
        // document id on every save. Everything else must be the same bytes.
        // Task 0050: the output also carries the dates, so both engines get the same one.
        todaysEngine.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-11-24T00:00:00Z"), java.time.ZoneOffset.UTC);
        searchableEngine.clock = todaysEngine.clock;
        byte[] today = redact(todaysEngine, null);
        assertArrayEquals(withoutDocumentId(today), withoutDocumentId(redact(todaysEngine, null)),
                "today's engine, two runs: only the document id differs");
        assertArrayEquals(withoutDocumentId(today), withoutDocumentId(redact(searchableEngine, RedactionOptions.IMAGE_ONLY)),
                "off, engine with OCR");
        assertArrayEquals(withoutDocumentId(today), withoutDocumentId(redact(searchableEngine, null)),
                "the old call, engine with OCR");
        try (PDDocument doc = Loader.loadPDF(today)) {
            assertTrue(text(doc, 1).isBlank(), "image only: no text on the redacted page");
        }
        assertTrue(verifyOutput(today, false).safe());
    }

    @Test
    void anEngineWithoutOcrRefusesASearchableRequest() {
        assertThrows(RedactionEngineException.class, () -> redact(todaysEngine, RedactionOptions.SEARCHABLE));
    }

    @Test
    void aPlantedLeakInTheTextLayerFailsClosed() throws Exception {
        byte[] output = redact(searchableEngine, RedactionOptions.SEARCHABLE);
        byte[] leaked = withInvisibleText(output, 1, SSN); // test only: a layer that kept the value
        VerificationResult result = verifyOutput(leaked, true);
        assertFalse(result.safe());
        assertEquals(TechnicalFailureCode.REDACTION_VALUE_IN_OUTPUT_TEXT, result.failureCode());
        assertFalse(result.failureReason().contains("7531"), "the reason names no value");
        // Written with spaces instead of dashes, it is still the value.
        VerificationResult spaced = verifyOutput(withInvisibleText(output, 1, "412 55 7531"), true);
        assertEquals(TechnicalFailureCode.REDACTION_VALUE_IN_OUTPUT_TEXT, spaced.failureCode());
    }

    @Test
    void aValueOnAnUntouchedPageFailsClosedInBothModes() throws Exception {
        byte[] today = redact(todaysEngine, null);
        byte[] leaked = withVisibleText(today, 2, "Copy: " + DOB);
        assertEquals(TechnicalFailureCode.REDACTION_VALUE_IN_OUTPUT_TEXT, verifyOutput(leaked, false).failureCode());
        assertEquals(TechnicalFailureCode.REDACTION_VALUE_IN_OUTPUT_TEXT, verifyOutput(leaked, true).failureCode());
    }

    @Test
    void imageOnlyModeStillRefusesAnyTextOnARedactedPage() throws Exception {
        byte[] leaked = withInvisibleText(redact(todaysEngine, null), 1, "harmless words");
        assertEquals(TechnicalFailureCode.REDACTION_TEXT_LAYER_REMAINS, verifyOutput(leaked, false).failureCode());
        assertTrue(verifyOutput(leaked, true).safe(), "with the layer on, text without a value is allowed");
    }

    @Test
    void theValueHolderNeverPrintsAValueAndIgnoresShortOnes() {
        RedactedValues values = RedactedValues.of(List.of(SSN, CARD, DOB, "720"));
        assertEquals(3, values.size(), "a 3-character value cannot be told apart from other numbers");
        assertEquals("RedactedValues[count=3]", values.toString());
        assertTrue(values.appearsIn("ssn 412 55 7531 end"));
        assertTrue(values.appearsIn("card4000056655665556x"), "a long value inside one word");
        assertTrue(values.appearsIn("41 25 57 531"), "the same characters split differently still count (fail closed)");
        assertFalse(values.appearsIn("4125 5753 page 1 of 2, 03/14/1963, 4000 0566"), "other numbers do not match");
        assertFalse(values.appearsIn("Score 720"));
    }

    // ------------------------------------------------------------------ helpers

    private byte[] redact(PdfBoxBurnFlatRedactionAdapter engine, RedactionOptions options) throws Exception {
        RedactionOutput output = options == null
                ? engine.redact(new ByteArrayInputStream(source), targets)
                : engine.redact(new ByteArrayInputStream(source), targets, options);
        assertEquals(3, output.redactionsApplied());
        try (var content = output.redactedPdfContent(); InputStream in = content.openStream()) {
            return in.readAllBytes();
        }
    }

    /** The PDF bytes with the trailer's /ID [&lt;hex&gt; &lt;hex&gt;] replaced by zeros of the same length. */
    private static byte[] withoutDocumentId(byte[] pdf) {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/ID \\[<([0-9A-F]+)> <([0-9A-F]+)>\\]").matcher(s);
        assertTrue(m.find(), "the output has a document id");
        StringBuilder b = new StringBuilder(s);
        for (int g = 1; g <= 2; g++) {
            for (int i = m.start(g); i < m.end(g); i++) b.setCharAt(i, '0');
        }
        assertFalse(m.find(), "one document id only");
        return b.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private VerificationResult verifyOutput(byte[] output, boolean searchable) {
        return verification.verify(new ByteArrayInputStream(output), 2, targets, searchable,
                RedactedValues.of(List.of(SSN, CARD, DOB)));
    }

    private static String text(PDDocument doc, int page) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(doc);
    }

    private static String decompressedContent(PDDocument doc) throws Exception {
        StringBuilder all = new StringBuilder();
        for (PDPage page : doc.getPages()) {
            try (InputStream in = page.getContents()) {
                all.append(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1)).append('\n');
            }
        }
        return all.toString();
    }

    /** Full value, the value without separators, and its last 4, in a text and in that text without spaces. */
    private static void assertNoValue(String where, String text) {
        String compact = text.replaceAll("\\s+", "");
        for (String value : List.of(SSN, CARD, DOB)) {
            String digits = value.replaceAll("[^0-9]", "");
            for (String needle : List.of(value, digits, digits.substring(digits.length() - 4))) {
                assertFalse(text.contains(needle) || compact.contains(needle),
                        () -> where + " holds part of a redacted value");
            }
        }
    }

    private static byte[] withInvisibleText(byte[] pdf, int page, String words) throws Exception {
        return withText(pdf, page, words, RenderingMode.NEITHER);
    }

    private static byte[] withVisibleText(byte[] pdf, int page, String words) throws Exception {
        return withText(pdf, page, words, RenderingMode.FILL);
    }

    private static byte[] withText(byte[] pdf, int page, String words, RenderingMode mode) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage p = doc.getPage(page - 1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, p, PDPageContentStream.AppendMode.APPEND, true)) {
                cs.beginText();
                cs.setRenderingMode(mode);
                cs.setFont(FONT, 10);
                cs.newLineAtOffset(72, 100);
                cs.showText(words);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
