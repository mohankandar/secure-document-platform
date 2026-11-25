package com.securedoc.platform.infrastructure.compliance;

import com.securedoc.platform.domain.error.LvErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0082: writes the error code guide to {@code target/LexaVault-error-codes.pdf} in every build that runs the tests,
 * then opens that file and checks that every code and all its texts are in it, in the catalogue's order.
 */
class ErrorCodeGuidePdfTest {

    static final Path FILE = Path.of("target", "LexaVault-error-codes.pdf");

    @Test
    void thePdfListsEveryCodeWithAllItsTexts() throws IOException {
        byte[] pdf = ErrorCodeGuidePdf.render();
        Files.createDirectories(FILE.getParent());
        Files.write(FILE, pdf);
        assertArrayEquals(pdf, ErrorCodeGuidePdf.render(), "the same texts give the same file");

        try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(FILE))) {
            assertEquals("en-US", doc.getDocumentCatalog().getLanguage());
            assertEquals(ErrorCodeGuidePdf.TITLE, doc.getDocumentInformation().getTitle());
            assertTrue(doc.getDocumentCatalog().getViewerPreferences().displayDocTitle());
            PDFTextStripper stripper = new PDFTextStripper();
            int total = doc.getNumberOfPages();
            List<String> pages = new ArrayList<>();
            for (int page = 1; page <= total; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(doc).replaceAll("\\s+", " ");
                assertTrue(text.contains("Page " + page + " of " + total), "page number on page " + page);
                pages.add(text);
            }
            for (LvErrorCode.Area area : LvErrorCode.Area.values()) {
                String heading = area.name() + ": " + LvErrorCode.areaName(area);
                int page = 0;
                while (!pages.get(page).contains(heading)) page++;
                int codes = LvErrorCode.inArea(area).size();
                assertTrue(pages.get(0).contains(area.name() + " " + LvErrorCode.areaName(area) + " " + codes
                        + (codes == 1 ? " code " : " codes ") + (page + 1) + " "), "contents: " + heading);
            }
            int from = 0;
            int at = 0;
            for (LvErrorCode code : LvErrorCode.values()) {
                String heading = code.code() + " " + code.title() + " Means";
                while (from < total && pages.get(from).indexOf(heading, at) < 0) {
                    from++;
                    at = 0;
                }
                assertTrue(from < total, heading + " is not in the PDF, or not in order");
                at = pages.get(from).indexOf(heading, at);
                // This entry only: up to the next code's heading on the page (an entry is never split over two pages).
                int next = -1;
                if (code.ordinal() + 1 < LvErrorCode.values().length) {
                    LvErrorCode following = LvErrorCode.values()[code.ordinal() + 1];
                    next = pages.get(from).indexOf(following.code() + " " + following.title() + " Means", at);
                }
                String entry = next < 0 ? pages.get(from).substring(at) : pages.get(from).substring(at, next);
                for (String words : List.of("Means " + code.description(), "Kind " + ErrorCodeGuidePdf.KINDS.get(code.kind()),
                        "Who acts " + ErrorCodeGuidePdf.whoActs(code), "HTTP status " + code.httpStatusLabel(),
                        "What to do 1. " + code.whatToDoSteps().get(0))) {
                    assertTrue(entry.contains(words), code.code() + ": " + words);
                }
                for (int i = 0; i < code.whatToDoSteps().size(); i++) {
                    assertTrue(entry.contains((i + 1) + ". " + code.whatToDoSteps().get(i)), code.code() + ": step " + (i + 1));
                }
                assertTrue(entry.contains("Support tells the customer " + code.supportTells()), code.code() + ": " + code.supportTells());
                assertEquals(code.reserved(), entry.substring(0, entry.indexOf("Who acts")).contains("Reserved: not used yet."), code.code());
            }
            int bookmarks = 0;
            for (PDOutlineItem area : doc.getDocumentCatalog().getDocumentOutline().children()) {
                for (PDOutlineItem ignored : area.children()) bookmarks++;
            }
            assertEquals(LvErrorCode.values().length, bookmarks);
        }
    }
}
