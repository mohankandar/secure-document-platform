package com.securedoc.platform.infrastructure.redaction;

import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionOutput;
import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionTarget;
import com.securedoc.platform.infrastructure.pdf.PdfBoxTempFileSupport;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfBoxSelectivePermanentRedactionTest {

    private final PdfBoxBurnFlatRedactionAdapter redaction = new PdfBoxBurnFlatRedactionAdapter();
    private final PdfBoxRedactionVerificationAdapter verification = new PdfBoxRedactionVerificationAdapter();

    @Test
    void preservesNativeTextOnUntouchedStaticPagesAndBurnFlattensOnlyTargetPage() throws Exception {
        byte[] source = threePageTextPdf(false);
        RedactionTarget target = RedactionTarget.of(2, 0.10, 0.08, 0.36, 0.09);

        RedactionOutput output = redaction.redact(new ByteArrayInputStream(source), List.of(target));

        assertEquals(3, output.pagesRendered());
        assertEquals(1, output.redactionsApplied());
        try (var content = output.redactedPdfContent()) {
            try (var verifyStream = content.openStream()) {
                assertTrue(verification.verify(verifyStream, 3, List.of(target)).safe());
            }
            try (var inspectStream = content.openStream();
                 var staged = PdfBoxTempFileSupport.stage(inspectStream, "test-redaction-")) {
                PDDocument document = staged.document();
                assertTrue(pageText(document, 1).contains("KEEP-ONE"));
                assertTrue(pageText(document, 2).isBlank(), "target page must have no text layer");
                assertTrue(pageText(document, 3).contains("KEEP-THREE"));
                assertEquals(0, imageXObjectCount(document.getPage(0)),
                        "untouched static page should stay native/vector");
                assertTrue(imageXObjectCount(document.getPage(1)) >= 1,
                        "target page should be a flattened page image");
                assertEquals(0, imageXObjectCount(document.getPage(2)),
                        "untouched static page should stay native/vector");
            }
        }
    }

    @Test
    void zeroTargetsPreserveStaticDocumentTextInsteadOfRasterizingEverything() throws Exception {
        byte[] source = threePageTextPdf(false);

        RedactionOutput output = redaction.redact(new ByteArrayInputStream(source), List.of());

        assertEquals(3, output.pagesRendered());
        assertEquals(0, output.redactionsApplied());
        try (var content = output.redactedPdfContent()) {
            try (var verifyStream = content.openStream()) {
                assertTrue(verification.verify(verifyStream, 3, List.of()).safe());
            }
            try (var inspectStream = content.openStream();
                 var staged = PdfBoxTempFileSupport.stage(inspectStream, "test-redaction-")) {
                PDDocument document = staged.document();
                assertTrue(pageText(document, 1).contains("KEEP-ONE"));
                assertTrue(pageText(document, 2).contains("SECRET-TWO"));
                assertTrue(pageText(document, 3).contains("KEEP-THREE"));
                assertEquals(0, imageXObjectCount(document.getPage(0)));
                assertEquals(0, imageXObjectCount(document.getPage(1)));
                assertEquals(0, imageXObjectCount(document.getPage(2)));
            }
        }
    }

    @Test
    void pageWithAnnotationIsRasterizedSoActiveAnnotationIsNotCopied() throws Exception {
        byte[] source = threePageTextPdf(true);

        RedactionOutput output = redaction.redact(new ByteArrayInputStream(source), List.of());

        try (var content = output.redactedPdfContent();
             var inspectStream = content.openStream();
             var staged = PdfBoxTempFileSupport.stage(inspectStream, "test-redaction-")) {
            PDDocument document = staged.document();
            assertTrue(pageText(document, 1).contains("KEEP-ONE"));
            assertTrue(pageText(document, 2).contains("SECRET-TWO"));
            assertTrue(pageText(document, 3).isBlank(),
                    "annotated page should be flattened instead of copying active annotation content");
            assertTrue(document.getPage(2).getAnnotations().isEmpty());
            assertTrue(imageXObjectCount(document.getPage(2)) >= 1);
        }
    }

    @Test
    void sourceDocumentMetadataIsNotCopied() throws Exception {
        byte[] source = threePageTextPdf(false);
        RedactionOutput output = redaction.redact(new ByteArrayInputStream(source), List.of());

        try (var content = output.redactedPdfContent();
             var inspectStream = content.openStream();
             var staged = PdfBoxTempFileSupport.stage(inspectStream, "test-redaction-")) {
            assertNull(staged.document().getDocumentInformation().getTitle());
        }
    }

    @Test
    void verifierRejectsAnyTextLayerOnAPageThatContainsARedactionTarget() throws Exception {
        RedactionTarget target = RedactionTarget.of(1, 0.10, 0.08, 0.36, 0.09);
        byte[] unsafe = pageWithBlackTargetAndRemainingText();

        var result = verification.verify(unsafe, 1, List.of(target));

        assertFalse(result.safe());
        assertTrue(result.hasExtractableText());
    }

    private byte[] threePageTextPdf(boolean annotateThirdPage) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("SENSITIVE-METADATA");
            document.setDocumentInformation(info);

            addTextPage(document, "KEEP-ONE");
            addTextPage(document, "SECRET-TWO");
            PDPage third = addTextPage(document, "KEEP-THREE");
            if (annotateThirdPage) {
                PDAnnotationText annotation = new PDAnnotationText();
                annotation.setContents("annotation payload");
                // PDF annotations require a page rectangle. Without /Rect, PDFBox 3 may
                // attempt to synthesize an appearance stream with a null bounding box
                // while rendering, which makes this fixture malformed and causes an NPE
                // before the selective-redaction behavior can actually be exercised.
                annotation.setRectangle(new PDRectangle(72, 640, 160, 40));
                third.getAnnotations().add(annotation);
            }

            document.save(out);
            return out.toByteArray();
        }
    }

    private PDPage addTextPage(PDDocument document, String text) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(font, 18);
            content.newLineAtOffset(72, 700);
            content.showText(text);
            content.endText();
        }
        return page;
    }

    private byte[] pageWithBlackTargetAndRemainingText() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(0, 0, 0);
                content.addRect(61, 657, 221, 71);
                content.fill();
                content.beginText();
                content.setFont(font, 12);
                content.newLineAtOffset(72, 500);
                content.showText("OTHER-EXTRACTABLE-TEXT");
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private String pageText(PDDocument document, int pageNumber) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(pageNumber);
        stripper.setEndPage(pageNumber);
        return stripper.getText(document).trim();
    }

    private int imageXObjectCount(PDPage page) throws Exception {
        int count = 0;
        if (page.getResources() == null) {
            return 0;
        }
        for (var name : page.getResources().getXObjectNames()) {
            PDXObject xObject = page.getResources().getXObject(name);
            if (xObject instanceof PDImageXObject) {
                count++;
            }
        }
        return count;
    }
}
