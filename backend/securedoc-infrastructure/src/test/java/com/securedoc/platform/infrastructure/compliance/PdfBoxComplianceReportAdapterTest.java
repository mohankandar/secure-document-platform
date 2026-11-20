package com.securedoc.platform.infrastructure.compliance;

import com.securedoc.platform.application.compliance.ComplianceEdition;
import com.securedoc.platform.application.compliance.ComplianceReportContent;
import com.securedoc.platform.application.compliance.ComplianceReportContent.AppendixRow;
import com.securedoc.platform.application.compliance.ComplianceReportContent.ChangeRow;
import com.securedoc.platform.application.compliance.ComplianceReportContent.CountRow;
import com.securedoc.platform.application.compliance.ComplianceReportContent.LabelValue;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0017 B3: layout, wording, fonts and paging of the compliance report PDF. */
class PdfBoxComplianceReportAdapterTest {

    static final String FINGERPRINT = "0123456789abcdef".repeat(4);
    private final PdfBoxComplianceReportAdapter adapter = new PdfBoxComplianceReportAdapter();

    @Test
    void summaryEditionHasCoverAndSectionsWithCanvasWording() throws IOException {
        byte[] pdf = adapter.render(content(ComplianceEdition.SUMMARY, List.of()), FINGERPRINT);
        writeSample("summary-sample", pdf);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertEquals(3, doc.getNumberOfPages());
            for (PDPage page : doc.getPages()) {
                assertEquals(PDRectangle.LETTER.getWidth(), page.getMediaBox().getWidth(), 0.01);
                assertEquals(PDRectangle.LETTER.getHeight(), page.getMediaBox().getHeight(), 0.01);
            }
            String cover = text(doc, 1, 1);
            for (String expected : List.of("LexaVault", "COMPLIANCE REPORT · SUMMARY EDITION", "Test entity A",
                    "Jul 1 – Sep 30, 2026", "Created by", "Pat Example, Business Administrator", "Created at",
                    "Test entity A only. Other entities are never included.",
                    "Summary: audit activity as counts. The full audit trail edition is created by the IT Administrator.",
                    "Contents", "Redaction policy and its changes", "Keeping and deleting, with proof that deletions ran",
                    "Cases and review", "Audit activity", "LexaVault keeps no redacted values.",
                    "Fingerprint (SHA-256)", PdfBoxComplianceReportAdapter.grouped(FINGERPRINT),
                    "Fingerprint of this report's contents. LexaVault's audit trail records it together with the",
                    "Created by LexaVault · contains no borrower names, file names, loan numbers or redacted values",
                    "Page 1 of 3")) {
                assertTrue(cover.contains(expected), "cover lacks: " + expected + "\n" + cover);
            }
            String rest = text(doc, 2, 3);
            for (String expected : List.of("Compliance report · Test entity A · Jul 1 – Sep 30, 2026",
                    "1 · Redaction policy", "At the end of the period", "Sensitive types found and redacted",
                    "Social Security Number, Date of birth", "Changes during the period", "Redacted without review at: 90% → 85%",
                    "2 · Keeping and deleting", "Originals kept for", "72 hours after delivery", "Proof that deletions ran",
                    "Originals due for deletion", "Deleted on time", "Late or failed", "1,402",
                    "No redacted values are kept.", "3 · Cases and review", "OF THE DELIVERED CASES",
                    "4 · Audit activity", "Access refused (wrong role)",
                    "The full audit trail edition, created by the IT Administrator, adds every record as an appendix",
                    "Fingerprint 0123 4567 … cdef · created by LexaVault", "Page 2 of 3", "Page 3 of 3")) {
                assertTrue(rest.contains(expected), "sections lack: " + expected + "\n" + rest);
            }
            assertFalse(rest.contains("Appendix"));
            // Task 0018 A3/A4/A5: no rejected rows, no lifecycle change table without changes, the note under section 3.
            assertFalse(rest.contains("Rejected") || rest.contains("rejected"), rest);
            assertTrue(rest.contains(OUTCOMES_NOTE), rest);
            String section2 = rest.substring(rest.indexOf("2 · Keeping and deleting"), rest.indexOf("Proof that deletions ran"));
            assertFalse(section2.contains("Changes during the period"), section2);
            // No licence: no customer name on the cover or in the header line.
            assertFalse(cover.contains("Acme"));
            assertPlexEmbedded(doc);
        }
    }

    @Test
    void customerNameFromTheLicenceIsOnTheCoverAndInTheHeaderLine() throws IOException {
        byte[] pdf = adapter.render(content(ComplianceEdition.SUMMARY, List.of(), "Acme Lending Group", List.of()),
                FINGERPRINT);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String cover = text(doc, 1, 1);
            assertTrue(cover.contains("Acme Lending Group Test entity A"), cover);
            // The cover has no header line (answer 1): the name appears once, as the title.
            assertEquals(1, cover.split("Acme Lending Group", -1).length - 1, cover);
            for (int p = 2; p <= doc.getNumberOfPages(); p++) {
                String page = text(doc, p, p);
                assertTrue(page.contains("Compliance report · Test entity A · Jul 1 – Sep 30, 2026 Acme Lending Group"),
                        "header line on page " + p + ": " + page);
            }
        }
    }

    @Test
    void lifecycleChangesAreShownInSectionTwoWhenThereAreAny() throws IOException {
        List<ChangeRow> changes = List.of(
                new ChangeRow("Aug 3, 09:15", "Pat Example", "Originals kept for: 72 → 48 hours"),
                new ChangeRow("Aug 3, 09:15", "Pat Example", "Supervisor approval before delivery: Required → Not required"));
        byte[] pdf = adapter.render(content(ComplianceEdition.SUMMARY, List.of(), null, changes), FINGERPRINT);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String rest = text(doc, 2, doc.getNumberOfPages());
            String section2 = rest.substring(rest.indexOf("2 · Keeping and deleting"), rest.indexOf("Proof that deletions ran"));
            for (String expected : List.of("Changes during the period", "WHEN", "WHO", "WHAT CHANGED", "Aug 3, 09:15",
                    "Originals kept for: 72 → 48 hours",
                    "Supervisor approval before delivery: Required → Not required")) {
                assertTrue(section2.contains(expected), "section 2 lacks: " + expected + "\n" + section2);
            }
        }
    }

    @Test
    void footerFingerprintGroupsAreMonoAndTheGapsAreSans() {
        // Task 0018 A7: a mono space read as a double space ("cacc  4c84"); the gaps are now drawn in the sans font.
        String shortFp = PdfBoxComplianceReportAdapter.shortFingerprint(FINGERPRINT);
        assertEquals("0123 4567 … cdef", shortFp);
        var parts = PdfBoxComplianceReportAdapter.footerFingerprintParts(shortFp);
        assertEquals(List.of("0123", " ", "4567", " … ", "cdef"),
                parts.stream().map(PdfBoxComplianceReportAdapter.FingerprintPart::text).toList());
        assertEquals(List.of(true, false, true, false, true),
                parts.stream().map(PdfBoxComplianceReportAdapter.FingerprintPart::mono).toList());
    }

    @Test
    void fullEditionAppendixPagesFiveThousandRowsWithHeadersOnEveryPage() throws IOException {
        List<AppendixRow> rows = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            rows.add(new AppendixRow(String.format("2026-08-%02d 10:%02d:%02d", 1 + i % 28, i % 60, i % 60),
                    i % 3 == 0 ? "Redacted a Social Security Number" : "Deleted the original after its retention period",
                    i % 2 == 0 ? "LexaVault (automatic)" : "Pat Example", "Success",
                    new UUID(0, i).toString(), i % 5 == 0 ? "Detection completed: findings=3, autoConfirmed=2, requiresReview=true" : "—"));
        }
        byte[] pdf = adapter.render(content(ComplianceEdition.FULL, rows), FINGERPRINT);
        writeSample("full-sample", pdf);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int pages = doc.getNumberOfPages();
            assertTrue(pages > 60, "5,000 rows need many pages: " + pages);
            String cover = text(doc, 1, 1);
            assertTrue(cover.contains("COMPLIANCE REPORT · FULL AUDIT TRAIL EDITION"));
            assertTrue(cover.contains("Appendix · Full audit trail"));
            assertTrue(text(doc, 3, 3).contains("The full audit trail follows as an appendix, with cases by case ID only."));
            assertTrue(text(doc, 4, 4).contains("Appendix · Full audit trail"));
            for (int p = 4; p <= pages; p++) {
                String page = text(doc, p, p);
                assertTrue(page.contains("Page " + p + " of " + pages), "footer on page " + p);
                assertTrue(page.contains("TIME") && page.contains("CASE ID") && page.contains("DETAIL"),
                        "column header repeated on page " + p);
            }
            // Every one of the 5,000 rows is printed once, in order (case IDs may wrap inside their cell).
            String all = text(doc, 4, pages).replaceAll("\\s+", "");
            int from = 0;
            for (int i = 0; i < 5000; i++) {
                int at = all.indexOf(new UUID(0, i).toString(), from);
                assertTrue(at >= 0, "row " + i + " printed, after row " + (i - 1));
                from = at + 1;
            }
        }
    }

    @Test
    void whatLexaVaultDidForYouIsTheLastTableOfSectionThreeAndItsSignsAreDrawn() throws IOException {
        ComplianceReportContent base = content(ComplianceEdition.SUMMARY, List.of());
        ComplianceReportContent.Cases cases = new ComplianceReportContent.Cases(base.cases().outcomes(),
                base.cases().outcomesNote(), base.cases().ofDelivered(),
                List.of(new ComplianceReportContent.ValueRow("Handled without a person", "348", "67%"),
                        new ComplianceReportContent.ValueRow("Pages checked (in 1,873 documents)", "14,220", null),
                        new ComplianceReportContent.ValueRow("Sensitive items redacted", "3,912", null),
                        new ComplianceReportContent.ValueRow("Review time saved (estimate)", "≈ 161 h", null)),
                "Estimate: pages in cases that needed no person × 1 minute per page, rounded down. "
                        + "Pages a person reviewed count as zero.");
        ComplianceReportContent withTable = new ComplianceReportContent(base.edition(), null, base.entityName(),
                base.periodWords(), base.periodEndWords(), base.createdBy(), base.createdAt(), base.policy(),
                base.retention(), cases, base.activity(), base.appendix());
        byte[] pdf = adapter.render(withTable, FINGERPRINT);
        writeSample("summary-did-for-you", pdf);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String all = text(doc, 1, doc.getNumberOfPages());
            String section3 = all.substring(all.indexOf("3 · Cases and review"), all.indexOf("4 · Audit activity"));
            for (String expected : List.of("WHAT LEXAVAULT DID FOR YOU NUMBER SHARE", "Handled without a person 348 67%",
                    "Pages checked (in 1,873 documents) 14,220 —", "Sensitive items redacted 3,912 —",
                    "Review time saved (estimate) ≈ 161 h —",
                    "Estimate: pages in cases that needed no person × 1 minute per page, rounded down. Pages a person reviewed count as zero.")) {
                assertTrue(section3.contains(expected), "section 3 lacks: " + expected + "\n" + section3);
            }
            assertTrue(section3.indexOf("Finished by hand after a processing stop") < section3.indexOf("WHAT LEXAVAULT DID FOR YOU"),
                    "after the delivered table");
            assertFalse(section3.contains("?"), "no replaced character");
        }
        assertFalse(adapter.render(base, FINGERPRINT).length == pdf.length && java.util.Arrays.equals(adapter.render(base, FINGERPRINT), pdf));
    }

    @Test
    void charactersTheFontCannotDrawAreReplacedNotFatal() throws IOException {
        ComplianceReportContent base = content(ComplianceEdition.SUMMARY, List.of());
        ComplianceReportContent odd = new ComplianceReportContent(base.edition(), null, "Entity \u0007😀 B",
                base.periodWords(), base.periodEndWords(), "Name‮with control, Business Administrator",
                base.createdAt(), base.policy(), base.retention(), base.cases(), base.activity(), base.appendix());
        try (PDDocument doc = Loader.loadPDF(adapter.render(odd, FINGERPRINT))) {
            assertEquals(3, doc.getNumberOfPages());
        }
    }

    /** Only when -Dcompliance.sample.dir is set: the PDF and its first pages as PNG, for a visual check. */
    static void writeSample(String name, byte[] pdf) throws IOException {
        String dir = System.getProperty("compliance.sample.dir");
        if (dir == null) return;
        java.nio.file.Path out = java.nio.file.Path.of(dir);
        java.nio.file.Files.createDirectories(out);
        java.nio.file.Files.write(out.resolve(name + ".pdf"), pdf);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(doc);
            for (int i = 0; i < Math.min(5, doc.getNumberOfPages()); i++) {
                javax.imageio.ImageIO.write(renderer.renderImageWithDPI(i, 80), "png",
                        out.resolve(name + "-p" + (i + 1) + ".png").toFile());
            }
        }
    }

    static String text(PDDocument doc, int from, int to) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(from);
        stripper.setEndPage(to);
        return stripper.getText(doc).replaceAll("\\s+", " ");
    }

    private static void assertPlexEmbedded(PDDocument doc) throws IOException {
        Set<String> names = new HashSet<>();
        for (PDPage page : doc.getPages()) {
            for (var name : page.getResources().getFontNames()) {
                PDFont font = page.getResources().getFont(name);
                names.add(font.getName());
                assertTrue(font.isEmbedded(), font.getName() + " embedded");
            }
        }
        assertTrue(names.stream().anyMatch(n -> n.contains("IBMPlexSans")), names.toString());
        assertTrue(names.stream().anyMatch(n -> n.contains("IBMPlexMono")), names.toString());
    }

    static final String OUTCOMES_NOTE = "Delivered counts cases first delivered in the period, whenever they were "
            + "received, as on the Reports page. Shares are of cases received.";

    static ComplianceReportContent content(ComplianceEdition edition, List<AppendixRow> appendix) {
        return content(edition, appendix, null, List.of());
    }

    static ComplianceReportContent content(ComplianceEdition edition, List<AppendixRow> appendix, String customer,
                                           List<ChangeRow> lifecycleChanges) {
        return new ComplianceReportContent(edition, customer, "Test entity A", "Jul 1 – Sep 30, 2026", "Sep 30",
                "Pat Example, " + (edition == ComplianceEdition.FULL ? "IT Administrator" : "Business Administrator"),
                "Nov 19, 2026, 8:14 PM EST",
                new ComplianceReportContent.Policy(false, List.of(
                        new LabelValue("Sensitive types found and redacted", "Social Security Number, Date of birth"),
                        new LabelValue("Sent to a person", "Only uncertain findings"),
                        new LabelValue("Redacted without review at", "85% confidence or higher"),
                        new LabelValue("Labeled last-four SSN treated as sensitive", "Yes")),
                        List.of(new ChangeRow("Aug 12, 10:42", "Pat Example", "Redacted without review at: 90% → 85%"),
                                new ChangeRow("Aug 12, 10:42", "Pat Example", "Credit score turned on"))),
                new ComplianceReportContent.Retention(true, List.of(
                        new LabelValue("Originals kept for", "72 hours after delivery"),
                        new LabelValue("Redacted copies kept for", "24 hours"),
                        new LabelValue("Stopped cases kept for", "7 days"),
                        new LabelValue("Supervisor approval before delivery", "Not required")),
                        lifecycleChanges, 1402, 1402, 0, "Each deletion removes the original together with its extracted text and its "
                        + "text recognition output. No case was still within its keep time on Sep 30."),
                new ComplianceReportContent.Cases(
                        List.of(new CountRow("Received", 1512, "100%"), new CountRow("Delivered", 1431, "95%"),
                                new CountRow("Still open on Sep 30", 59, "4%")),
                        OUTCOMES_NOTE,
                        List.of(new CountRow("No review needed", 973, "68%"), new CountRow("Checked by a reviewer", 458, "32%"),
                                new CountRow("Finished by hand after a processing stop", 9, "1%"))),
                List.of(new CountRow("Redaction policy changes", 3, null), new CountRow("Keeping and deleting changes", 0, null),
                        new CountRow("Findings decided by a reviewer", 4210, null), new CountRow("Cases approved for delivery", 0, null),
                        new CountRow("Originals deleted", 1402, null),
                        new CountRow("Report exports (CSV)", 6, null), new CountRow("Compliance reports created", 1, null),
                        new CountRow("Access refused (wrong role)", 4, null)),
                appendix);
    }
}
