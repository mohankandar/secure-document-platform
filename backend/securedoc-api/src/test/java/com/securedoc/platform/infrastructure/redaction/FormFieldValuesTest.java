package com.securedoc.platform.infrastructure.redaction;

import com.securedoc.platform.application.detection.service.FindingLocationMapper;
import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.port.out.OcrPort;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionTarget;
import com.securedoc.platform.application.port.out.RedactionVerificationPort.RedactedValues;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort;
import com.securedoc.platform.application.port.out.TextExtractionPort.PageText;
import com.securedoc.platform.application.port.out.TextExtractionPort.TextExtractionOutput;
import com.securedoc.platform.infrastructure.detection.LocalRuleBasedDetectionAdapter;
import com.securedoc.platform.infrastructure.extraction.HybridPdfTextExtractionAdapter;
import com.securedoc.platform.infrastructure.extraction.PdfBoxTextExtractionAdapter;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrProperties;
import com.securedoc.platform.infrastructure.pdf.PdfFormUnreadableException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.form.PDVariableText;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0053: a sensitive value that is only in a form field is read, found, covered and absent from the output. Real
 * text reading, rules, redaction and final check. Fake values only.
 */
class FormFieldValuesTest {
    private static final String SSN = "412-55-7531";
    private static final String CARD = "4000 0566 5566 5556";
    private static final String PAGE = "A loan form with enough ordinary words on the page to be read as text";

    private final HybridPdfTextExtractionAdapter extraction = new HybridPdfTextExtractionAdapter(
            new PdfBoxTextExtractionAdapter(), new OcrPort() {
                @Override public OcrPageOutput recognize(OcrPageInput input) {
                    throw new AssertionError("typed pages need no OCR");
                }
            }, new TesseractOcrProperties());
    private final LocalRuleBasedDetectionAdapter rules = new LocalRuleBasedDetectionAdapter();

    @Test
    void anSsnOnlyInATextFieldIsFoundRedactedAndAbsentFromTheOutput() throws Exception {
        for (int rotation : new int[]{0, 90, 180, 270}) {
            byte[] input = pdf(doc -> {
                PDPage page = page(doc, PAGE);
                page.setRotation(rotation);
                text(doc, page, "ssn", "Borrower SSN", SSN, 500);
            });
            check(input, Map.of("SSN", List.of(SSN)));
        }
    }

    @Test
    void aCardNumberOnlyInAComboBoxIsFoundRedactedAndAbsentFromTheOutput() throws Exception {
        byte[] input = pdf(doc -> {
            PDPage page = page(doc, PAGE);
            PDComboBox combo = new PDComboBox(form(doc));
            combo.setOptions(List.of("a", "b"), List.of("Other", "Card number " + CARD));
            place(doc, page, combo, "choice", "Payment", 500);
            combo.setValue("b");
        });
        check(input, Map.of("CREDIT_CARD_NUMBER", List.of(CARD)));
    }

    @Test
    void aMixedPageKeepsItsOwnTextPlacesAndFindsBoth() throws Exception {
        byte[] plain = pdf(doc -> {
            PDPage page = page(doc, PAGE);
            for (int y = 680; y > 610; y -= 14) line(doc, page, y, PAGE + " and more of the same on every line");
            line(doc, page, 600, "Co-borrower SSN: 219-09-9999");
        });
        byte[] mixed = pdf(doc -> {
            PDPage page = page(doc, PAGE);
            for (int y = 680; y > 610; y -= 14) line(doc, page, y, PAGE + " and more of the same on every line");
            line(doc, page, 600, "Co-borrower SSN: 219-09-9999");
            text(doc, page, "ssn", "Borrower SSN", SSN, 500);
            PDListBox list = new PDListBox(form(doc));
            list.setOptions(List.of("Paid with " + CARD, "Other"));
            place(doc, page, list, "list", "Card number", 400);
            list.setValue("Paid with " + CARD);
        });
        String own = page(plain).text();
        PageText with = page(mixed);
        assertTrue(with.text().startsWith(own), "the page's own text and offsets stay as they were");
        assertEquals(page(plain).spans(), with.spans().subList(0, page(plain).spans().size()));
        check(mixed, Map.of("SSN", List.of("219-09-9999", SSN), "CREDIT_CARD_NUMBER", List.of(CARD)));
    }

    @Test
    void aValueOnlyInTheDrawnAppearanceOrOnAParentFieldIsRead() throws Exception {
        byte[] drawn = pdf(doc -> {
            PDPage page = page(doc, PAGE);
            PDTextField field = text(doc, page, "ssn", "Borrower SSN", SSN, 500);
            field.getCOSObject().setString(COSName.V, "something else");
        });
        check(drawn, Map.of("SSN", List.of(SSN)));
        byte[] comb = pdf(doc -> {
            PDPage page = page(doc, PAGE);
            PDTextField field = new PDTextField(form(doc));
            field.setMaxLen(11);
            field.setComb(true);
            place(doc, page, field, "ssn", "Borrower SSN", 500);
            field.setValue(SSN);
        });
        check(comb, Map.of("SSN", List.of(SSN)));
    }

    @Test
    void pagesWithoutFieldsAndFieldsNobodySeesChangeNothing() throws Exception {
        byte[] plain = pdf(doc -> page(doc, PAGE));
        String own = page(plain).text();
        byte[] notText = pdf(doc -> {
            PDPage page = page(doc, PAGE);
            var box = new org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox(form(doc));
            box.getCOSObject().setName(COSName.V, "Yes");
            place(doc, page, box, "box", "Agree", 500);
            PDTextField hidden = text(doc, page, "hidden", "SSN", SSN, 450);
            hidden.getWidgets().get(0).setHidden(true);
            PDTextField outside = text(doc, page, "outside", "SSN", SSN, 400);
            outside.getWidgets().get(0).setRectangle(new PDRectangle(5000, 5000, 100, 20));
            text(doc, page, "empty", "SSN", "", 350);
        });
        assertEquals(own, page(notText).text());
        // What is hidden or outside the page is not read because it does not reach the output.
        byte[] output = redact(notText, List.of());
        assertFalse(everything(output).contains(SSN));
    }

    @Test
    void aPageReadInAPageBatchIsThePageOfTheWholeDocument() throws Exception {
        byte[] input = pdf(doc -> {
            page(doc, PAGE);
            text(doc, page(doc, PAGE), "ssn", "Borrower SSN", SSN, 500);
        });
        TextExtractionOutput whole = extraction.extract(new ByteArrayInputStream(input), "application/pdf");
        TextExtractionOutput batch = extraction.extractPages(new ByteArrayInputStream(input), "application/pdf",
                java.util.Set.of(2));
        assertEquals(whole.pages().get(1), batch.pages().get(0));
        assertEquals(whole.pages(), new PdfBoxTextExtractionAdapter()
                .extract(new ByteArrayInputStream(input), "application/pdf").pages());
    }

    @Test
    void aFormThatIsOnlyXfaStopsWithItsCodeAndAFormWithBothIsReadByItsFields() throws Exception {
        byte[] xfaOnly = pdf(doc -> {
            page(doc, "Please wait. If this message is not replaced, your viewer cannot show this form.");
            form(doc).getCOSObject().setString(COSName.XFA, "<xdp/>");
        });
        byte[] dynamic = pdf(doc -> {
            text(doc, page(doc, PAGE), "ssn", "SSN", SSN, 500);
            form(doc).getCOSObject().setString(COSName.XFA, "<xdp/>");
            doc.getDocumentCatalog().getCOSObject().setBoolean(COSName.getPDFName("NeedsRendering"), true);
        });
        for (byte[] input : List.of(xfaOnly, dynamic)) {
            RuntimeException refused = assertThrows(PdfFormUnreadableException.class, () -> extraction.extract(
                    new ByteArrayInputStream(input), "application/pdf"));
            assertEquals("LV-PRC-010", LvErrorCodes.forPlatformFailure(
                    new RuntimeException("Text extraction failed", refused)).code(), "as the Text step reports it");
            assertThrows(PdfFormUnreadableException.class, () -> extraction.extractPages(
                    new ByteArrayInputStream(input), "application/pdf", java.util.Set.of(1)));
        }
        byte[] both = pdf(doc -> {
            text(doc, page(doc, PAGE), "ssn", "Borrower SSN", SSN, 500);
            form(doc).getCOSObject().setString(COSName.XFA, "<xdp>XFA-ONLY-TEXT</xdp>");
        });
        check(both, Map.of("SSN", List.of(SSN)));
        assertFalse(everything(redact(both, List.of())).contains("XFA-ONLY-TEXT"));
    }

    // --------------------------------------------------------------------------------------------- helpers

    /** Finds exactly these values, covers them, and the final check and the bytes agree they are gone. */
    private void check(byte[] input, Map<String, List<String>> expected) throws Exception {
        PageText page = page(input);
        List<RedactionTarget> targets = new ArrayList<>();
        List<String> values = new ArrayList<>();
        Map<String, List<String>> found = new LinkedHashMap<>();
        for (var e : rules.detect(List.of(new SensitiveDataDetectionPort.PageInput(1, page.text()))).entities()) {
            String type = e.entityType().equals("CREDIT_CARD") ? "CREDIT_CARD_NUMBER" : e.entityType();
            if (!expected.containsKey(type)) continue;
            String value = page.text().substring(e.startOffset(), e.endOffset());
            found.computeIfAbsent(type, t -> new ArrayList<>()).add(value);
            values.add(value);
            var box = FindingLocationMapper.resolveBoundingBox(page, e.startOffset(), e.endOffset()).orElseThrow();
            targets.add(RedactionTarget.of(1, box.left(), box.top(), box.width(), box.height()));
        }
        assertEquals(expected.keySet(), found.keySet());
        expected.forEach((type, wanted) -> assertTrue(found.get(type).containsAll(wanted), type + " " + found.get(type).size()));
        byte[] output = redact(input, targets);
        try (InputStream in = new ByteArrayInputStream(output)) {
            assertTrue(new PdfBoxRedactionVerificationAdapter().verify(in, 1, targets, false,
                    RedactedValues.of(values)).safe());
        }
        String all = everything(output);
        values.forEach(v -> assertFalse(all.contains(v)));
        // Covered on the picture too, not only gone from the text: nothing of the field's box is light.
        try (PDDocument doc = Loader.loadPDF(output)) {
            var image = new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImageWithDPI(0, 72);
            for (RedactionTarget t : targets) {
                int x = (int) ((t.left() + t.width() / 2) * image.getWidth());
                int y = (int) ((t.top() + t.height() / 2) * image.getHeight());
                assertTrue((image.getRGB(x, y) & 0xff) < 60, "the box is over the field");
            }
        }
    }

    private PageText page(byte[] input) {
        return extraction.extract(new ByteArrayInputStream(input), "application/pdf").pages().get(0);
    }

    private static byte[] redact(byte[] input, List<RedactionTarget> targets) throws Exception {
        var output = new PdfBoxBurnFlatRedactionAdapter().redact(new ByteArrayInputStream(input), targets);
        try (var content = output.redactedPdfContent(); InputStream in = content.openStream()) {
            return in.readAllBytes();
        }
    }

    private static String everything(byte[] pdf) throws Exception {
        StringBuilder all = new StringBuilder(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            all.append(new PDFTextStripper().getText(doc));
            for (var key : doc.getDocument().getXrefTable().keySet()) {
                var object = doc.getDocument().getObjectFromPool(key).getObject();
                all.append(object);
                if (object instanceof org.apache.pdfbox.cos.COSStream stream) {
                    try (InputStream in = stream.createInputStream()) {
                        all.append(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1));
                    }
                }
            }
        }
        return all.toString();
    }

    private interface Build {
        void into(PDDocument doc) throws Exception;
    }

    private static byte[] pdf(Build build) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            build.into(doc);
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static PDPage page(PDDocument doc, String text) throws Exception {
        PDPage page = new PDPage();
        doc.addPage(page);
        line(doc, page, 700, text);
        return page;
    }

    private static void line(PDDocument doc, PDPage page, float y, String text) throws Exception {
        try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            cs.beginText();
            cs.setFont(font(), 12);
            cs.newLineAtOffset(72, y);
            cs.showText(text);
            cs.endText();
        }
    }

    private static PDType1Font font() {
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    private static PDAcroForm form(PDDocument doc) {
        PDAcroForm form = doc.getDocumentCatalog().getAcroForm(null);
        if (form == null) {
            form = new PDAcroForm(doc);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("Helv"), font());
            form.setDefaultResources(resources);
            form.setDefaultAppearance("/Helv 12 Tf 0 g");
            doc.getDocumentCatalog().setAcroForm(form);
        }
        return form;
    }

    private static PDTextField text(PDDocument doc, PDPage page, String name, String label, String value, float y)
            throws Exception {
        PDTextField field = new PDTextField(form(doc));
        place(doc, page, field, name, label, y);
        field.setValue(value);
        return field;
    }

    private static void place(PDDocument doc, PDPage page, PDTerminalField field, String name, String label, float y)
            throws Exception {
        field.setPartialName(name + (int) y);
        field.setAlternateFieldName(label);
        if (field instanceof PDVariableText text) text.setDefaultAppearance("/Helv 12 Tf 0 g");
        List<PDField> fields = new ArrayList<>(form(doc).getFields());
        fields.add(field);
        form(doc).setFields(fields);
        var widget = field.getWidgets().get(0);
        widget.setRectangle(new PDRectangle(150, y, 260, 24));
        widget.setPage(page);
        page.getAnnotations().add(widget);
    }
}
