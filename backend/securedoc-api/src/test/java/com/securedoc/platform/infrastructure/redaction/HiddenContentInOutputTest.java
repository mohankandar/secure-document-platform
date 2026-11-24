package com.securedoc.platform.infrastructure.redaction;

import com.securedoc.platform.application.detection.service.FindingLocationMapper;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.OcrPort;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionOutput;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionTarget;
import com.securedoc.platform.application.port.out.RedactionVerificationPort.RedactedValues;
import com.securedoc.platform.application.port.out.RedactionVerificationPort.VerificationResult;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.infrastructure.detection.LocalRuleBasedDetectionAdapter;
import com.securedoc.platform.infrastructure.extraction.HybridPdfTextExtractionAdapter;
import com.securedoc.platform.infrastructure.extraction.PdfBoxTextExtractionAdapter;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.action.PDPageAdditionalActions;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0050: every kind of hidden content of an input page is gone from the redacted output, what is visible stays, and
 * a redacted value left anywhere in an output file is refused. Synthetic values only.
 */
class HiddenContentInOutputTest {
    private static final String SSN = "219-09-9999";
    private final PdfBoxBurnFlatRedactionAdapter redaction = new PdfBoxBurnFlatRedactionAdapter();
    private final PdfBoxRedactionVerificationAdapter verification = new PdfBoxRedactionVerificationAdapter();

    @Test
    void everyKindOfHiddenContentIsGoneAndTheVisiblePageStays() throws Exception {
        byte[] input = pdfWithEverythingHidden();
        Map<String, String> kinds = Map.ofEntries(Map.entry("info", "HID-INFO"), Map.entry("xmp", "HID-XMP"),
                Map.entry("page xmp", "HID-PAGEXMP"), Map.entry("script", "HID-JS"), Map.entry("page action", "HID-PAGEJS"),
                Map.entry("attachment", "HID-ATTACH"), Map.entry("attachment name", "HID-FILENAME"),
                Map.entry("comment", "HID-COMMENT"), Map.entry("link", "HID-LINK"), Map.entry("hidden layer", "HID-LAYER"),
                Map.entry("layer name", "HID-LAYERNAME"), Map.entry("actual text", "HID-ACTUAL"),
                Map.entry("private data", "HID-PIECE"), Map.entry("unused drawing", "HID-UNUSED"),
                Map.entry("field tooltip", "HID-TOOLTIP"), Map.entry("bookmark", "HID-BOOKMARK"));
        String before = everything(input);
        kinds.forEach((kind, text) -> assertTrue(before.contains(text), "the input holds it: " + kind));

        RedactionOutput result = redaction.redact(new ByteArrayInputStream(input), List.of());
        byte[] output = bytes(result);
        String after = everything(output);
        kinds.forEach((kind, text) -> assertFalse(after.contains(text), kind + " is still in the output"));
        assertEquals(1, count(new String(output, StandardCharsets.ISO_8859_1), "%%EOF"), "one revision, a new file");
        assertFalse(result.signatureRemoved());

        try (PDDocument doc = Loader.loadPDF(output)) {
            assertEquals(Set.of("Producer", "CreationDate", "ModDate"),
                    doc.getDocumentInformation().getMetadataKeys());
            assertEquals("LexaVault", doc.getDocumentInformation().getProducer());
            assertEquals(Set.of("Type", "Pages", "Version"), names(doc.getDocumentCatalog().getCOSObject(), "Version"));
            assertNull(doc.getDocumentCatalog().getAcroForm());
            String first = text(doc, 1);
            assertTrue(first.contains("VISIBLE PAGE ONE"), "the visible text stays as text");
            assertTrue(first.contains("FIELD SHOWN"), "a form field stays as it looks");
            assertTrue(first.contains("VISIBLE LAYER"), "a visible layer stays");
            assertTrue(text(doc, 2).isBlank(), "a page with a hidden layer is drawn, never copied");
            assertTrue(text(doc, 3).contains("VISIBLE PAGE THREE"));
        }
        assertTrue(verify(output, 3, RedactedValues.of(List.of(SSN))).safe());
    }

    @Test
    void aValueInAFormFieldThatWasRedactedElsewhereMakesTheFinalCheckRefuse() throws Exception {
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = page(doc, "Loan file");
            field(doc, page, SSN);
            input = save(doc);
        }
        byte[] output = bytes(redaction.redact(new ByteArrayInputStream(input), List.of()));
        VerificationResult result = verify(output, 1, RedactedValues.of(List.of(SSN)));
        assertFalse(result.safe());
        assertEquals(TechnicalFailureCode.REDACTION_VALUE_IN_OUTPUT_TEXT, result.failureCode());
    }

    @Test
    void aRedactedValueAnywhereInTheFileIsRefused() throws Exception {
        for (String place : List.of("info", "name", "stream", "split text", "unused object", "invisible text", "utf16")) {
            byte[] output;
            try (PDDocument doc = new PDDocument()) {
                PDPage page = page(doc, "Nothing sensitive");
                switch (place) {
                    case "info" -> doc.getDocumentInformation().setTitle("Loan " + SSN);
                    case "name" -> page.getCOSObject().setItem(COSName.getPDFName("Tag219-09-9999"), COSName.A);
                    case "stream" -> {
                        COSStream data = doc.getDocument().createCOSStream();
                        try (var out = data.createOutputStream(COSName.FLATE_DECODE)) {
                            out.write(("<x>" + SSN + "</x>").getBytes(StandardCharsets.UTF_8));
                        }
                        page.getCOSObject().setItem(COSName.getPDFName("Data"), data);
                    }
                    case "split text" -> {
                        PDFormXObject form = new PDFormXObject(doc);
                        form.setBBox(new PDRectangle(100, 100));
                        try (var out = form.getContentStream().createOutputStream(COSName.FLATE_DECODE)) {
                            out.write("BT [(219) 20 (-09) 20 (-9999)] TJ ET".getBytes(StandardCharsets.US_ASCII));
                        }
                        page.getResources().add(form);
                    }
                    case "unused object" -> page.getCOSObject().setItem(COSName.getPDFName("X"),
                            new COSArray(List.of(new COSString("id " + SSN))));
                    case "invisible text" -> {
                        try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                                PDPageContentStream.AppendMode.APPEND, true)) {
                            cs.beginText();
                            cs.setRenderingMode(RenderingMode.NEITHER);
                            cs.setFont(font(), 10);
                            cs.newLineAtOffset(72, 300);
                            cs.showText(SSN);
                            cs.endText();
                        }
                    }
                    default -> page.getCOSObject().setString(COSName.getPDFName("T"), "é一 " + SSN);
                }
                output = save(doc);
            }
            VerificationResult result = verify(output, 1, RedactedValues.of(List.of(SSN)));
            assertFalse(result.safe(), place);
            assertEquals(TechnicalFailureCode.REDACTION_VALUE_IN_OUTPUT_TEXT, result.failureCode(), place);
            assertTrue(verify(output, 1, RedactedValues.of(List.of("888-77-6666"))).safe(), place + ": only that value");
        }
    }

    @Test
    void anOutputThatStillHoldsActiveOrHiddenContentIsRefused() throws Exception {
        for (String kind : List.of("Metadata", "OpenAction", "AcroForm", "AF", "AA")) {
            byte[] output;
            try (PDDocument doc = new PDDocument()) {
                page(doc, "Page");
                doc.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName(kind), new COSDictionary());
                output = save(doc);
            }
            assertEquals(TechnicalFailureCode.REDACTION_HIDDEN_CONTENT_REMAINS,
                    verify(output, 1, RedactedValues.none()).failureCode(), kind);
        }
    }

    @Test
    void numbersOfADrawingAreNotText() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = page(doc, "Page");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true)) {
                cs.setLineWidth(0.9999f);
                cs.moveTo(9999, 9999);
                cs.lineTo(10, 10);
                cs.stroke();
            }
            assertTrue(verify(save(doc), 1, RedactedValues.of(List.of("9999"))).safe());
        }
    }

    @Test
    void anSsnInInvisibleTextIsFoundAndRedacted() throws Exception {
        for (String how : List.of("invisible", "white")) {
            byte[] input;
            try (PDDocument doc = new PDDocument()) {
                PDPage page = page(doc, "A page with enough ordinary text to be read as text, for the loan file");
                try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.beginText();
                    if (how.equals("invisible")) cs.setRenderingMode(RenderingMode.NEITHER);
                    else cs.setNonStrokingColor(1f, 1f, 1f);
                    cs.setFont(font(), 12);
                    cs.newLineAtOffset(72, 500);
                    cs.showText("Borrower SSN: " + SSN);
                    cs.endText();
                }
                input = save(doc);
            }
            OcrPort noOcr = i -> {
                throw new AssertionError("no OCR needed");
            };
            TextExtractionPort.PageText page = new HybridPdfTextExtractionAdapter(new PdfBoxTextExtractionAdapter(),
                    noOcr, new TesseractOcrProperties()).extract(new ByteArrayInputStream(input), "application/pdf")
                    .pages().get(0);
            var found = new LocalRuleBasedDetectionAdapter().detect(List.of(
                    new SensitiveDataDetectionPort.PageInput(1, page.text()))).entities().stream()
                    .filter(e -> e.entityType().equals("SSN")).toList();
            assertEquals(1, found.size(), how);
            var box = FindingLocationMapper.resolveBoundingBox(page, found.get(0).startOffset(),
                    found.get(0).endOffset()).orElseThrow();
            RedactionTarget target = RedactionTarget.of(1, box.left(), box.top(), box.width(), box.height());
            byte[] output = bytes(redaction.redact(new ByteArrayInputStream(input), List.of(target)));
            try (InputStream in = new ByteArrayInputStream(output)) {
                assertTrue(verification.verify(in, 1, List.of(target), false, RedactedValues.of(List.of(SSN))).safe(), how);
            }
            assertFalse(everything(output).contains(SSN), how);
        }
    }

    @Test
    void aSignedInputIsReportedAndItsOutputIsNotSigned() throws Exception {
        byte[] input;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = page(doc, "Signed page");
            PDAcroForm form = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            PDSignatureField field = new PDSignatureField(form);
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setName("HID-SIGNER");
            field.getCOSObject().setItem(COSName.V, signature.getCOSObject());
            field.getWidgets().get(0).setPage(page);
            field.getWidgets().get(0).setRectangle(new PDRectangle(72, 72, 100, 30));
            page.getAnnotations().add(field.getWidgets().get(0));
            form.getFields().add(field);
            input = save(doc);
        }
        RedactionOutput result = redaction.redact(new ByteArrayInputStream(input), List.of());
        assertTrue(result.signatureRemoved());
        byte[] output = bytes(result);
        assertFalse(everything(output).contains("HID-SIGNER"));
        try (PDDocument doc = Loader.loadPDF(output)) {
            assertTrue(doc.getSignatureDictionaries().isEmpty());
        }
    }

    // ------------------------------------------------------------------------------------------------ the input

    private static byte[] pdfWithEverythingHidden() throws Exception {
        byte[] base;
        try (PDDocument doc = new PDDocument()) {
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("HID-INFO");
            doc.setDocumentInformation(info);
            PDMetadata xmp = new PDMetadata(doc);
            xmp.importXMPMetadata("<x>HID-XMP</x>".getBytes(StandardCharsets.UTF_8));
            doc.getDocumentCatalog().setMetadata(xmp);
            doc.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('HID-JS')"));

            PDEmbeddedFile file = new PDEmbeddedFile(doc,
                    new ByteArrayInputStream("HID-ATTACH".getBytes(StandardCharsets.UTF_8)));
            PDComplexFileSpecification spec = new PDComplexFileSpecification();
            spec.setFile("HID-FILENAME.txt");
            spec.setEmbeddedFile(file);
            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(Map.of("a", spec));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
            names.setEmbeddedFiles(tree);
            doc.getDocumentCatalog().setNames(names);
            var outline = new org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline();
            var item = new org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem();
            item.setTitle("HID-BOOKMARK");
            outline.addLast(item);
            doc.getDocumentCatalog().setDocumentOutline(outline);

            PDOptionalContentProperties layers = new PDOptionalContentProperties();
            PDOptionalContentGroup shown = new PDOptionalContentGroup("HID-LAYERNAME");
            PDOptionalContentGroup hidden = new PDOptionalContentGroup("hidden layer");
            layers.addGroup(shown);
            layers.addGroup(hidden);
            layers.setGroupEnabled(hidden, false);
            doc.getDocumentCatalog().setOCProperties(layers);

            PDPage one = page(doc, "VISIBLE PAGE ONE");
            PDMetadata pageXmp = new PDMetadata(doc);
            pageXmp.importXMPMetadata("<x>HID-PAGEXMP</x>".getBytes(StandardCharsets.UTF_8));
            one.setMetadata(pageXmp);
            PDPageAdditionalActions actions = new PDPageAdditionalActions();
            actions.setO(new PDActionJavaScript("HID-PAGEJS"));
            one.setActions(actions);
            COSDictionary piece = new COSDictionary();
            piece.setString(COSName.getPDFName("P"), "HID-PIECE");
            one.getCOSObject().setItem(COSName.getPDFName("PieceInfo"), piece);
            try (PDPageContentStream cs = new PDPageContentStream(doc, one, PDPageContentStream.AppendMode.APPEND, true)) {
                cs.beginMarkedContent(COSName.OC, shown);
                line(cs, 640, "VISIBLE LAYER");
                cs.endMarkedContent();
            }
            COSStream tagged = doc.getDocument().createCOSStream();
            try (var out = tagged.createOutputStream(COSName.FLATE_DECODE)) {
                out.write("\n/Span <</ActualText (HID-ACTUAL)>> BDC EMC\n".getBytes(StandardCharsets.US_ASCII));
            }
            COSBase contents = one.getCOSObject().getDictionaryObject(COSName.CONTENTS);
            COSArray both = contents instanceof COSArray a ? a : new COSArray(List.of(contents));
            both.add(tagged);
            one.getCOSObject().setItem(COSName.CONTENTS, both);
            PDFormXObject unused = new PDFormXObject(doc);
            unused.setBBox(new PDRectangle(100, 100));
            unused.setResources(new PDResources());
            try (var out = unused.getContentStream().createOutputStream(COSName.FLATE_DECODE)) {
                out.write("BT (HID-UNUSED) Tj ET".getBytes(StandardCharsets.US_ASCII));
            }
            one.getResources().add(unused);
            field(doc, one, "FIELD SHOWN");
            PDAnnotationText comment = new PDAnnotationText();
            comment.setContents("HID-COMMENT");
            comment.setRectangle(new PDRectangle(300, 640, 20, 20));
            one.getAnnotations().add(comment);
            PDAnnotationLink link = new PDAnnotationLink();
            PDActionURI uri = new PDActionURI();
            uri.setURI("https://example.test/HID-LINK");
            link.setAction(uri);
            link.setRectangle(new PDRectangle(72, 700, 100, 20));
            one.getAnnotations().add(link);

            PDPage two = page(doc, "VISIBLE PAGE TWO");
            try (PDPageContentStream cs = new PDPageContentStream(doc, two, PDPageContentStream.AppendMode.APPEND, true)) {
                cs.beginMarkedContent(COSName.OC, hidden);
                line(cs, 640, "HID-LAYER");
                cs.endMarkedContent();
            }
            page(doc, "VISIBLE PAGE THREE");
            base = save(doc);
        }
        return base;
    }

    private static PDPage page(PDDocument doc, String text) throws Exception {
        PDPage page = new PDPage();
        page.setResources(new PDResources());
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            line(cs, 700, text);
        }
        return page;
    }

    private static void field(PDDocument doc, PDPage page, String value) throws Exception {
        PDAcroForm form = new PDAcroForm(doc);
        doc.getDocumentCatalog().setAcroForm(form);
        PDResources resources = new PDResources();
        resources.put(COSName.getPDFName("Helv"), font());
        form.setDefaultResources(resources);
        form.setDefaultAppearance("/Helv 12 Tf 0 g");
        PDTextField field = new PDTextField(form);
        field.setPartialName("f");
        field.setAlternateFieldName("HID-TOOLTIP");
        field.setDefaultAppearance("/Helv 12 Tf 0 g");
        form.getFields().add(field);
        var widget = field.getWidgets().get(0);
        widget.setRectangle(new PDRectangle(72, 560, 200, 24));
        widget.setPage(page);
        page.getAnnotations().add(widget);
        field.setValue(value);
    }

    private static PDType1Font font() {
        return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    }

    private static void line(PDPageContentStream cs, float y, String text) throws Exception {
        cs.beginText();
        cs.setFont(font(), 12);
        cs.newLineAtOffset(72, y);
        cs.showText(text);
        cs.endText();
    }

    private static byte[] save(PDDocument doc) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] bytes(RedactionOutput output) throws Exception {
        try (var content = output.redactedPdfContent(); InputStream in = content.openStream()) {
            return in.readAllBytes();
        }
    }

    private VerificationResult verify(byte[] output, int pages, RedactedValues values) throws Exception {
        try (InputStream in = new ByteArrayInputStream(output)) {
            return verification.verify(in, pages, List.of(), false, values);
        }
    }

    private static String text(PDDocument doc, int page) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(doc);
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    private static Set<String> names(COSDictionary dictionary, String optional) {
        Set<String> names = new java.util.HashSet<>(Set.of(optional));
        dictionary.keySet().forEach(k -> names.add(k.getName()));
        return names;
    }

    /** Every string, name and decompressed stream of every object in the file. Written apart from the product's check. */
    private static String everything(byte[] pdf) throws Exception {
        StringBuilder all = new StringBuilder(new String(pdf, StandardCharsets.ISO_8859_1));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            collect(doc.getDocument().getTrailer(), all, seen);
            for (var key : doc.getDocument().getXrefTable().keySet()) {
                collect(doc.getDocument().getObjectFromPool(key), all, seen);
            }
        }
        return all.toString();
    }

    private static void collect(COSBase base, StringBuilder all, Set<COSBase> seen) throws Exception {
        COSBase item = base instanceof COSObject object ? object.getObject() : base;
        if (item == null) return;
        if (item instanceof COSString string) all.append('\n').append(string.getString());
        if (item instanceof COSName name) all.append('\n').append(name.getName());
        if (!seen.add(item)) return;
        if (item instanceof COSStream stream) {
            try (InputStream in = stream.createInputStream()) {
                all.append('\n').append(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
            }
        }
        if (item instanceof COSDictionary dictionary) {
            for (COSName key : dictionary.keySet()) {
                all.append('\n').append(key.getName());
                collect(dictionary.getItem(key), all, seen);
            }
        } else if (item instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) collect(array.get(i), all, seen);
        }
    }
}
