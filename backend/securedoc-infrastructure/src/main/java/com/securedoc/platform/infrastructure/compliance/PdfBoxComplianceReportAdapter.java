package com.securedoc.platform.infrastructure.compliance;

import com.securedoc.platform.application.compliance.ComplianceEdition;
import com.securedoc.platform.application.compliance.ComplianceReportContent;
import com.securedoc.platform.application.compliance.ComplianceReportContent.AppendixRow;
import com.securedoc.platform.application.compliance.ComplianceReportContent.ChangeRow;
import com.securedoc.platform.application.compliance.ComplianceReportContent.CountRow;
import com.securedoc.platform.application.compliance.ComplianceReportContent.ValueRow;
import com.securedoc.platform.application.compliance.ComplianceReportContent.LabelValue;
import com.securedoc.platform.application.port.out.ComplianceReportPdfPort;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lays out the compliance report with PDFBox on US Letter (task 0017 B3), following the design canvas: a cover, the
 * four sections, and in the full edition the appendix. IBM Plex Sans and Mono are embedded (subset) from
 * {@code /fonts/ibm-plex/} on the classpath; nothing is loaded from the internet. The document is built in memory
 * (with a temp-file-only stream cache for large appendices) and returned as bytes; it is never stored.
 *
 * <p>Sizes follow the canvas at 0.75 pt per CSS pixel (an 816 × 1056 px page is 612 × 792 pt).
 */
@Component
public class PdfBoxComplianceReportAdapter implements ComplianceReportPdfPort {

    private static final float W = PDRectangle.LETTER.getWidth();
    private static final float H = PDRectangle.LETTER.getHeight();
    private static final float SIDE = 54f;          // 72 px
    private static final float COVER_TOP = 54f;     // 72 px
    private static final float PAGE_TOP = 42f;      // 56 px
    private static final float CONTENT_W = W - 2 * SIDE;
    private static final float FOOTER_Y = 36f;

    private static final Color INK = new Color(0x15, 0x17, 0x1a);
    private static final Color MUTED = new Color(0x62, 0x68, 0x72);
    private static final Color BODY2 = new Color(0x3a, 0x3f, 0x46);
    private static final Color RULE = new Color(0xe1, 0xe3, 0xe6);
    private static final Color YELLOW = new Color(0xf4, 0xd0, 0x3f);
    private static final Color GREEN = new Color(0x1d, 0x7a, 0x4e);

    static final String FONT_DIR = "/fonts/ibm-plex/";
    static final String DASH = "—";

    @Override
    public byte[] render(ComplianceReportContent content, String fingerprint) {
        try (PDDocument doc = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
            Fonts fonts = new Fonts(doc);
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle("Compliance report");
            info.setCreator("LexaVault");
            info.setProducer("LexaVault");
            new Writer(doc, fonts, content, fingerprint).write();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("The compliance report PDF could not be laid out", e);
        }
    }

    /** The fingerprint in groups of 4: "7f3a 9c21 …". */
    static String grouped(String hex) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 4) {
            if (i > 0) out.append(' ');
            out.append(hex, i, Math.min(hex.length(), i + 4));
        }
        return out.toString();
    }

    /** "7f3a 9c21 … e9f3": the first two groups and the last. */
    static String shortFingerprint(String hex) {
        if (hex.length() < 12) return hex;
        return hex.substring(0, 4) + " " + hex.substring(4, 8) + " … " + hex.substring(hex.length() - 4);
    }

    /** One piece of the footer fingerprint: a hex group (mono) or the gap between groups (sans). */
    record FingerprintPart(String text, boolean mono) { }

    /** Splits the short fingerprint into hex groups and the gaps between them (task 0018 A7). */
    static List<FingerprintPart> footerFingerprintParts(String shortFingerprint) {
        List<FingerprintPart> parts = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[0-9a-f]+|[^0-9a-f]+").matcher(shortFingerprint);
        while (m.find()) {
            String part = m.group();
            parts.add(new FingerprintPart(part, Character.digit(part.charAt(0), 16) >= 0));
        }
        return parts;
    }

    /* ---------------- fonts ---------------- */

    static final class Fonts {
        final PDType0Font sans;
        final PDType0Font medium;
        final PDType0Font semibold;
        final PDType0Font mono;
        final PDType0Font monoMedium;
        private final Map<PDType0Font, Map<Integer, Boolean>> glyphs = new HashMap<>();

        Fonts(PDDocument doc) throws IOException {
            sans = load(doc, "IBMPlexSans-Regular.ttf");
            medium = load(doc, "IBMPlexSans-Medium.ttf");
            semibold = load(doc, "IBMPlexSans-SemiBold.ttf");
            mono = load(doc, "IBMPlexMono-Regular.ttf");
            monoMedium = load(doc, "IBMPlexMono-Medium.ttf");
        }

        private static PDType0Font load(PDDocument doc, String file) throws IOException {
            try (InputStream in = PdfBoxComplianceReportAdapter.class.getResourceAsStream(FONT_DIR + file)) {
                if (in == null) throw new IOException("Font resource missing: " + file);
                return PDType0Font.load(doc, in, true);
            }
        }

        /** The text with every character the font cannot draw (and every control character) replaced. */
        String safe(PDType0Font font, String text) {
            if (text == null) return "";
            Map<Integer, Boolean> known = glyphs.computeIfAbsent(font, f -> new HashMap<>());
            StringBuilder out = new StringBuilder(text.length());
            text.codePoints().forEach(cp -> {
                if (Character.isISOControl(cp)) {
                    out.append(' ');
                    return;
                }
                boolean ok = known.computeIfAbsent(cp, c -> {
                    try {
                        font.encode(new String(Character.toChars(c)));
                        return true;
                    } catch (IOException | IllegalArgumentException e) {
                        return false;
                    }
                });
                if (ok) out.appendCodePoint(cp);
                else out.append('?');
            });
            return out.toString();
        }

        float width(PDType0Font font, float size, String safeText) {
            try {
                return font.getStringWidth(safeText) / 1000f * size;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** A run of text in one font, for lines that mix bold and regular. */
    record Run(PDType0Font font, String text) { }

    /* ---------------- writer ---------------- */

    static final class Writer {
        private final PDDocument doc;
        private final Fonts f;
        private final ComplianceReportContent c;
        private final String fingerprint;
        private final List<PDPage> pages = new ArrayList<>();
        private PDPageContentStream cs;
        private float y;

        Writer(PDDocument doc, Fonts fonts, ComplianceReportContent content, String fingerprint) {
            this.doc = doc;
            this.f = fonts;
            this.c = content;
            this.fingerprint = fingerprint;
        }

        void write() throws IOException {
            cover();
            newPage();
            sectionPolicy();
            sectionRetention();
            noValuesBox();
            newPage();
            sectionCases();
            sectionActivity();
            if (!c.reading().empty()) sectionReading();
            if (c.edition() == ComplianceEdition.FULL) appendix();
            close();
            decorate();
        }

        /* ----- page handling ----- */

        private void newPage() throws IOException {
            close();
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            pages.add(page);
            cs = new PDPageContentStream(doc, page);
            y = H - PAGE_TOP - 8.25f - 16.5f; // below the header line (added in decorate)
        }

        private void close() throws IOException {
            if (cs != null) {
                cs.close();
                cs = null;
            }
        }

        private float bottom() {
            return FOOTER_Y + 8.25f + 16f;
        }

        private void ensure(float height) throws IOException {
            if (y - height < bottom()) newPage();
        }

        /** Headers and footers once the page count is known. */
        private void decorate() throws IOException {
            int total = pages.size();
            String customer = c.customerName();
            String headerLeft = "Compliance report · " + c.entityName() + " · " + c.periodWords();
            for (int i = 0; i < total; i++) {
                try (PDPageContentStream s = new PDPageContentStream(doc, pages.get(i),
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    String pageWords = "Page " + (i + 1) + " of " + total;
                    float pw = f.width(f.sans, 8.25f, pageWords);
                    if (i == 0) {
                        text(s, f.sans, 8.25f, MUTED, SIDE, FOOTER_Y,
                                "Created by LexaVault · contains no borrower names, file names, loan numbers or redacted values");
                    } else {
                        text(s, f.sans, 8.25f, MUTED, SIDE, H - PAGE_TOP - 8.25f, headerLeft);
                        if (customer != null && !customer.isBlank()) {
                            String safe = f.safe(f.sans, customer);
                            text(s, f.sans, 8.25f, MUTED, W - SIDE - f.width(f.sans, 8.25f, safe), H - PAGE_TOP - 8.25f, safe);
                        }
                        float x = SIDE;
                        x += text(s, f.sans, 8.25f, MUTED, x, FOOTER_Y, "Fingerprint ");
                        // Task 0018 A7: the groups in mono, the gaps in sans. A mono space is as wide as a digit
                        // and read as a double space at footer size; the text stays "cacc 4c84 … d0a7".
                        for (FingerprintPart part : footerFingerprintParts(shortFingerprint(fingerprint))) {
                            x += text(s, part.mono() ? f.mono : f.sans, 8.25f, MUTED, x, FOOTER_Y, part.text());
                        }
                        text(s, f.sans, 8.25f, MUTED, x, FOOTER_Y, " · created by LexaVault");
                    }
                    text(s, f.sans, 8.25f, MUTED, W - SIDE - pw, FOOTER_Y, pageWords);
                }
            }
        }

        /* ----- cover ----- */

        private void cover() throws IOException {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            pages.add(page);
            cs = new PDPageContentStream(doc, page);
            y = H - COVER_TOP;

            // Logo mark: two ink bars around a shorter yellow bar, then the name.
            float markTop = y - 2.5f;
            fillRect(SIDE, markTop - 2.25f, 12f, 2.25f, INK);
            fillRect(SIDE, markTop - 6.75f, 12f * 0.62f, 2.25f, YELLOW);
            fillRect(SIDE, markTop - 11.25f, 12f, 2.25f, INK);
            text(cs, f.semibold, 12f, INK, SIDE + 18f, y - 10.5f, "LexaVault");
            y -= 12f + 30f + 30f;

            String edition = c.edition() == ComplianceEdition.FULL
                    ? "Compliance report · full audit trail edition"
                    : "Compliance report · summary edition";
            tracked(cs, f.semibold, 9.75f, MUTED, SIDE, y - 9.75f, edition.toUpperCase(Locale.US), 0.78f);
            y -= 9.75f + 7.5f;
            if (c.customerName() != null && !c.customerName().isBlank()) {
                y = paragraph(f.semibold, 30f, INK, SIDE, y, CONTENT_W, c.customerName(), 1.15f);
            }
            y = paragraph(f.semibold, 30f, INK, SIDE, y, CONTENT_W, c.entityName(), 1.15f);
            y -= 7.5f;
            y = paragraph(f.sans, 13.5f, BODY2, SIDE, y, CONTENT_W, c.periodWords(), 1.3f);
            y -= 30f;

            String editionLine = c.edition() == ComplianceEdition.FULL
                    ? "Full audit trail: every record in the period is listed in the appendix, with cases by case ID only."
                    : "Summary: audit activity as counts. The full audit trail edition is created by the IT Administrator.";
            List<LabelValue> facts = List.of(
                    new LabelValue("Created by", c.createdBy()),
                    new LabelValue("Created at", c.createdAt()),
                    new LabelValue("Entity", c.entityName() + " only. Other entities are never included."),
                    new LabelValue("Edition", editionLine));
            for (LabelValue fact : facts) {
                text(cs, f.sans, 10.5f, MUTED, SIDE, y - 10.5f, fact.label());
                y = paragraph(f.sans, 10.5f, INK, SIDE + 135f, y, CONTENT_W - 135f, fact.value(), 1.35f) - 7.5f;
            }
            y -= 22.5f;

            text(cs, f.semibold, 10.5f, INK, SIDE, y - 10.5f, "Contents");
            y -= 10.5f + 4.5f;
            List<String> contents = new ArrayList<>(List.of("Redaction policy and its changes",
                    "Keeping and deleting, with proof that deletions ran", "Cases and review", "Audit activity"));
            if (c.edition() == ComplianceEdition.FULL) contents.add("Appendix · Full audit trail");
            for (int i = 0; i < contents.size(); i++) {
                String number = (i < 4 ? (i + 1) + "." : "");
                text(cs, f.sans, 10.5f, INK, SIDE + 2f, y - 13.5f, number);
                text(cs, f.sans, 10.5f, INK, SIDE + 15f, y - 13.5f, contents.get(i));
                y -= 17.85f;
            }
            y -= 22.5f;

            box(y, List.of(new Run(f.semibold, "LexaVault keeps no redacted values."), new Run(f.sans,
                    " While an original document exists, authorized people may see it. LexaVault never saves a "
                            + "redacted value on its own, in full or in part. Once the original is deleted, nothing in "
                            + "LexaVault records what was under a redaction.")), 10.5f, 12f, 13.5f);

            // Fingerprint block, at the bottom above the footer.
            float caption = 9f;
            List<String> captionLines = wrap(f.sans, caption, CONTENT_W,
                    "Fingerprint of this report's contents. LexaVault's audit trail records it together with the "
                            + "SHA-256 of this file, so the file can be checked against it.");
            List<String> hexLines = wrap(f.mono, 9.75f, CONTENT_W, grouped(fingerprint));
            float blockH = 12f + 9.75f + 4.5f + hexLines.size() * 13f + 4.5f + captionLines.size() * 12f;
            float top = FOOTER_Y + 8.25f + 30f + blockH;
            line(SIDE, top, W - SIDE, top, RULE, 0.75f);
            float by = top - 12f;
            text(cs, f.semibold, 9.75f, INK, SIDE, by - 9.75f, "Fingerprint (SHA-256)");
            by -= 9.75f + 4.5f;
            for (String l : hexLines) {
                text(cs, f.mono, 9.75f, INK, SIDE, by - 9.75f, l);
                by -= 13f;
            }
            by -= 4.5f;
            for (String l : captionLines) {
                text(cs, f.sans, caption, MUTED, SIDE, by - caption, l);
                by -= 12f;
            }
        }

        /* ----- sections ----- */

        private void sectionPolicy() throws IOException {
            heading("1 · Redaction policy");
            sub("At the end of the period" + (c.policy().unchangedSinceBefore() ? " (unchanged since before this period)" : ""));
            labelTable(c.policy().atEnd());
            y -= 4.5f;
            sub("Changes during the period");
            if (c.policy().changes().isEmpty()) {
                y = paragraph(f.sans, 9.75f, BODY2, SIDE, y, CONTENT_W, "No changes during the period.", 1.5f);
            } else {
                changeTable(c.policy().changes());
            }
            y -= 16.5f;
        }

        private void changeTable(List<ChangeRow> rows) throws IOException {
            float[] cols = {90f, 112.5f, CONTENT_W - 202.5f};
            tableHeader(cols, new String[]{"When", "Who", "What changed"}, new boolean[3]);
            for (ChangeRow r : rows) {
                tableRow(cols, new Cell[]{new Cell(f.mono, r.when()), new Cell(f.sans, r.who()),
                        new Cell(f.sans, r.what())}, new boolean[3], 9.75f);
            }
        }

        private void sectionRetention() throws IOException {
            heading("2 · Keeping and deleting");
            if (c.retention().unchangedSinceBefore()) sub("At the end of the period (unchanged since before this period)");
            labelTable(c.retention().settings());
            y -= 4.5f;
            if (!c.retention().changes().isEmpty()) {
                // Task 0018 A4: shown only when there were changes.
                sub("Changes during the period");
                changeTable(c.retention().changes());
                y -= 4.5f;
            }
            sub("Proof that deletions ran");
            ensure(52f);
            float gap = 6f;
            float tw = (CONTENT_W - 2 * gap) / 3f;
            String[] labels = {"Originals due for deletion", "Deleted on time", "Late or failed"};
            long[] values = {c.retention().due(), c.retention().onTime(), c.retention().lateOrFailed()};
            float th = 45f;
            for (int i = 0; i < 3; i++) {
                float x = SIDE + i * (tw + gap);
                roundRect(x, y - th, tw, th, 4.5f, RULE);
                text(cs, f.sans, 9.75f, MUTED, x + 9f, y - 7.5f - 9.75f, labels[i]);
                text(cs, f.monoMedium, 15f, i == 1 ? GREEN : INK, x + 9f, y - 7.5f - 9.75f - 4f - 15f,
                        String.format(Locale.US, "%,d", values[i]));
            }
            y -= th + 9f;
            y = paragraph(f.sans, 9.75f, BODY2, SIDE, y, CONTENT_W, c.retention().note(), 1.5f);
            y -= 16.5f;
        }

        private void noValuesBox() throws IOException {
            box(y, List.of(new Run(f.semibold, "No redacted values are kept."), new Run(f.sans,
                    " After deletion, nothing in LexaVault records what was under a redaction. The redacted copy "
                            + "delivered to the destination holds only blacked-out areas.")), 9.75f, 9f, 10.5f);
        }

        private void sectionCases() throws IOException {
            heading("3 · Cases and review");
            countTable("Outcome", "Cases", c.cases().outcomes(), true);
            if (c.cases().outcomesNote() != null && !c.cases().outcomesNote().isBlank()) {
                // Task 0018 A5: small grey text under the first table, as the canvas's notes.
                y -= 4.5f;
                y = paragraph(f.sans, 8.25f, MUTED, SIDE, y, CONTENT_W, c.cases().outcomesNote(), 1.5f);
            }
            y -= 7.5f;
            countTable("Of the delivered cases", "Cases", c.cases().ofDelivered(), true);
            if (!c.cases().didForYou().isEmpty()) {
                // Task 0020 B4: "What LexaVault did for you", same table style, with its grey line.
                y -= 7.5f;
                valueTable("What LexaVault did for you", "Number", c.cases().didForYou());
                if (c.cases().didForYouNote() != null && !c.cases().didForYouNote().isBlank()) {
                    y -= 4.5f;
                    y = paragraph(f.sans, 8.25f, MUTED, SIDE, y, CONTENT_W, c.cases().didForYouNote(), 1.5f);
                }
            }
            y -= 16.5f;
        }

        private void valueTable(String first, String valueLabel, List<ValueRow> rows) throws IOException {
            float[] cols = new float[]{CONTENT_W - 165f, 82.5f, 82.5f};
            boolean[] right = new boolean[]{false, true, true};
            tableHeader(cols, new String[]{first, valueLabel, "Share"}, right);
            for (ValueRow r : rows) {
                tableRow(cols, new Cell[]{new Cell(f.sans, r.label()), new Cell(f.mono, r.value()),
                        new Cell(f.mono, r.share() == null ? DASH : r.share())}, right, 9.75f);
            }
        }

        private void sectionActivity() throws IOException {
            heading("4 · Audit activity");
            y = paragraph(f.sans, 9.75f, BODY2, SIDE, y, CONTENT_W,
                    "Counts of recorded actions in " + c.entityName() + ". Every action is kept in LexaVault's audit trail.", 1.5f);
            y -= 7.5f;
            countTable("Action", "Count", c.activity(), false);
            y -= 7.5f;
            String closing = c.edition() == ComplianceEdition.FULL
                    ? "The full audit trail follows as an appendix, with cases by case ID only."
                    : "The full audit trail edition, created by the IT Administrator, adds every record as an appendix, with cases by case ID only.";
            y = paragraph(f.sans, 9.75f, MUTED, SIDE, y, CONTENT_W, closing, 1.5f);
        }

        /** Task 0064. */
        private void sectionReading() throws IOException {
            y -= 16.5f;
            ensure(150f);
            heading("5 · How pages were read");
            countTable("Pages searched in the period", "Pages", c.reading().pages(), true);
            if (!c.reading().models().isEmpty()) {
                y -= 7.5f;
                float[] cols = {140f, 100f, CONTENT_W - 300f, 60f};
                boolean[] right = {false, false, false, true};
                tableHeader(cols, new String[]{"AI model", "Version", "Digest", "Documents"}, right);
                for (ComplianceReportContent.ModelRow m : c.reading().models()) {
                    tableRow(cols, new Cell[]{new Cell(f.sans, m.name()), new Cell(f.sans, m.version()),
                            new Cell(f.mono, m.digest()), new Cell(f.mono, Long.toString(m.documents()))}, right, 8.25f);
                }
            }
            if (c.reading().note() != null) {
                y -= 4.5f;
                y = paragraph(f.sans, 8.25f, MUTED, SIDE, y, CONTENT_W, c.reading().note(), 1.5f);
            }
        }

        private void appendix() throws IOException {
            newPage();
            heading("Appendix · Full audit trail");
            y = paragraph(f.sans, 9.75f, BODY2, SIDE, y, CONTENT_W, "Every audit record in " + c.entityName()
                    + " from " + c.periodWords() + ", oldest first. Times are in the time zone of “Created at”. "
                    + "Cases are shown by case ID only; a detail is printed only for actions whose detail holds "
                    + "counts, settings, codes and IDs.", 1.5f);
            y -= 7.5f;
            float[] cols = {62f, 112f, 84f, 40f, 98f, CONTENT_W - 396f};
            String[] head = {"Time", "Action", "Person", "Result", "Case ID", "Detail"};
            boolean[] right = new boolean[6];
            if (c.appendix().isEmpty()) {
                y = paragraph(f.sans, 9.75f, BODY2, SIDE, y, CONTENT_W, "No audit records in the period.", 1.5f);
                return;
            }
            appendixHeader(cols, head, right);
            for (AppendixRow r : c.appendix()) {
                Cell[] cells = {new Cell(f.mono, r.time()), new Cell(f.sans, r.action()), new Cell(f.sans, r.person()),
                        new Cell(f.sans, r.result()), new Cell(f.mono, r.caseId()), new Cell(f.mono, r.detail())};
                float h = rowHeight(cols, cells, 7f, 9f);
                if (y - h < bottom()) {
                    newPage();
                    appendixHeader(cols, head, right);
                }
                drawRow(cols, cells, right, 7f, 9f, 3f);
            }
        }

        private void appendixHeader(float[] cols, String[] head, boolean[] right) throws IOException {
            float x = SIDE;
            for (int i = 0; i < cols.length; i++) {
                tracked(cs, f.semibold, 7f, MUTED, x + 3f, y - 3f - 7f, head[i].toUpperCase(Locale.US), 0.42f);
                x += cols[i];
            }
            y -= 3f + 7f + 3f;
            line(SIDE, y, W - SIDE, y, RULE, 0.75f);
        }

        /* ----- building blocks ----- */

        private record Cell(PDType0Font font, String text) { }

        private void heading(String title) throws IOException {
            ensure(15f + 40f);
            text(cs, f.semibold, 15f, INK, SIDE, y - 15f, title);
            y -= 15f + 7.5f;
        }

        private void sub(String title) throws IOException {
            ensure(9.75f + 30f);
            y = paragraph(f.medium, 9.75f, INK, SIDE, y, CONTENT_W, title, 1.4f);
            y -= 3f;
        }

        private void labelTable(List<LabelValue> rows) throws IOException {
            float[] cols = {180f, CONTENT_W - 180f};
            for (LabelValue r : rows) {
                tableRow(cols, new Cell[]{new Cell(f.sans, r.label()), new Cell(f.sans, r.value())}, new boolean[2],
                        9.75f, MUTED);
            }
        }

        private void countTable(String first, String countLabel, List<CountRow> rows, boolean withShare)
                throws IOException {
            float[] cols = withShare ? new float[]{CONTENT_W - 165f, 82.5f, 82.5f} : new float[]{CONTENT_W - 82.5f, 82.5f};
            boolean[] right = withShare ? new boolean[]{false, true, true} : new boolean[]{false, true};
            tableHeader(cols, withShare ? new String[]{first, countLabel, "Share"} : new String[]{first, countLabel}, right);
            for (CountRow r : rows) {
                String n = String.format(Locale.US, "%,d", r.count());
                Cell[] cells = withShare
                        ? new Cell[]{new Cell(f.sans, r.label()), new Cell(f.mono, n), new Cell(f.mono, r.share() == null ? DASH : r.share())}
                        : new Cell[]{new Cell(f.sans, r.label()), new Cell(f.mono, n)};
                tableRow(cols, cells, right, 9.75f);
            }
        }

        private void tableHeader(float[] cols, String[] head, boolean[] right) throws IOException {
            ensure(8.25f + 9f + 30f);
            float x = SIDE;
            for (int i = 0; i < cols.length; i++) {
                String t = head[i].toUpperCase(Locale.US);
                float tx = right[i] ? x + cols[i] - 6f - trackedWidth(f.semibold, 8.25f, t, 0.5f) : x + 6f;
                tracked(cs, f.semibold, 8.25f, MUTED, tx, y - 4.5f - 8.25f, t, 0.5f);
                x += cols[i];
            }
            y -= 4.5f + 8.25f + 4.5f;
            line(SIDE, y, W - SIDE, y, RULE, 0.75f);
        }

        private void tableRow(float[] cols, Cell[] cells, boolean[] right, float size) throws IOException {
            tableRow(cols, cells, right, size, null);
        }

        private void tableRow(float[] cols, Cell[] cells, boolean[] right, float size, Color firstColor) throws IOException {
            float lh = size * 1.4f;
            float h = rowHeight(cols, cells, size, lh) + 4.5f * 2 - 3f * 2;
            ensure(h);
            drawRow(cols, cells, right, size, lh, 4.5f, firstColor);
        }

        private float rowHeight(float[] cols, Cell[] cells, float size, float lh) {
            int lines = 1;
            for (int i = 0; i < cells.length; i++) {
                lines = Math.max(lines, wrap(cells[i].font(), size, cols[i] - 6f * (cells.length > 3 ? 1 : 2), cells[i].text()).size());
            }
            return lines * lh + 3f * 2;
        }

        private void drawRow(float[] cols, Cell[] cells, boolean[] right, float size, float lh, float pad) throws IOException {
            drawRow(cols, cells, right, size, lh, pad, null);
        }

        private void drawRow(float[] cols, Cell[] cells, boolean[] right, float size, float lh, float pad, Color firstColor)
                throws IOException {
            float inset = cells.length > 3 ? 3f : 6f;
            List<List<String>> wrapped = new ArrayList<>();
            int lines = 1;
            for (int i = 0; i < cells.length; i++) {
                List<String> w = wrap(cells[i].font(), size, cols[i] - inset * 2, cells[i].text());
                wrapped.add(w);
                lines = Math.max(lines, w.size());
            }
            float x = SIDE;
            for (int i = 0; i < cells.length; i++) {
                float ly = y - pad - size;
                for (String l : wrapped.get(i)) {
                    float tx = right[i] ? x + cols[i] - inset - f.width(cells[i].font(), size, l) : x + inset;
                    text(cs, cells[i].font(), size, i == 0 && firstColor != null ? firstColor : INK, tx, ly, l);
                    ly -= lh;
                }
                x += cols[i];
            }
            y -= pad * 2 + (lines - 1) * lh + size;
            line(SIDE, y, W - SIDE, y, RULE, 0.75f);
        }

        /** A bordered box of mixed runs, starting at {@code top}. */
        private void box(float top, List<Run> runs, float size, float padV, float padH) throws IOException {
            float lh = size * 1.5f;
            List<List<Run>> lines = wrapRuns(runs, size, CONTENT_W - 2 * padH);
            float h = padV * 2 + lines.size() * lh - (lh - size) / 2;
            if (top - h < bottom()) {
                newPage();
                top = y;
            }
            roundRect(SIDE, top - h, CONTENT_W, h, 4.5f, INK);
            float ly = top - padV - size;
            for (List<Run> l : lines) {
                float x = SIDE + padH;
                for (Run r : l) x += text(cs, r.font(), size, INK, x, ly, r.text());
                ly -= lh;
            }
            y = top - h - 16.5f;
        }

        private float paragraph(PDType0Font font, float size, Color color, float x, float top, float width,
                                String text, float lineHeight) throws IOException {
            float lh = size * lineHeight;
            float ly = top;
            for (String l : wrap(font, size, width, text)) {
                if (ly - lh < bottom() && cs != null && pages.size() > 1) {
                    newPage();
                    ly = y;
                }
                this.text(cs, font, size, color, x, ly - size, l);
                ly -= lh;
            }
            return ly;
        }

        /* ----- primitives ----- */

        private float text(PDPageContentStream s, PDType0Font font, float size, Color color, float x, float baseline,
                           String raw) throws IOException {
            String safe = f.safe(font, raw);
            if (safe.isEmpty()) return 0f;
            s.beginText();
            s.setFont(font, size);
            s.setNonStrokingColor(color);
            s.newLineAtOffset(x, baseline);
            s.showText(safe);
            s.endText();
            return f.width(font, size, safe);
        }

        private void tracked(PDPageContentStream s, PDType0Font font, float size, Color color, float x, float baseline,
                             String raw, float spacing) throws IOException {
            String safe = f.safe(font, raw);
            s.beginText();
            s.setFont(font, size);
            s.setCharacterSpacing(spacing);
            s.setNonStrokingColor(color);
            s.newLineAtOffset(x, baseline);
            s.showText(safe);
            s.setCharacterSpacing(0f);
            s.endText();
        }

        private float trackedWidth(PDType0Font font, float size, String raw, float spacing) {
            String safe = f.safe(font, raw);
            return f.width(font, size, safe) + spacing * safe.codePointCount(0, safe.length());
        }

        private void fillRect(float x, float yy, float w, float h, Color color) throws IOException {
            cs.setNonStrokingColor(color);
            cs.addRect(x, yy, w, h);
            cs.fill();
        }

        private void line(float x1, float y1, float x2, float y2, Color color, float width) throws IOException {
            cs.setStrokingColor(color);
            cs.setLineWidth(width);
            cs.moveTo(x1, y1);
            cs.lineTo(x2, y2);
            cs.stroke();
        }

        private void roundRect(float x, float yy, float w, float h, float r, Color color) throws IOException {
            float k = 0.5523f * r;
            cs.setStrokingColor(color);
            cs.setLineWidth(0.75f);
            cs.moveTo(x + r, yy);
            cs.lineTo(x + w - r, yy);
            cs.curveTo(x + w - r + k, yy, x + w, yy + r - k, x + w, yy + r);
            cs.lineTo(x + w, yy + h - r);
            cs.curveTo(x + w, yy + h - r + k, x + w - r + k, yy + h, x + w - r, yy + h);
            cs.lineTo(x + r, yy + h);
            cs.curveTo(x + r - k, yy + h, x, yy + h - r + k, x, yy + h - r);
            cs.lineTo(x, yy + r);
            cs.curveTo(x, yy + r - k, x + r - k, yy, x + r, yy);
            cs.closePath();
            cs.stroke();
        }

        /* ----- wrapping ----- */

        List<String> wrap(PDType0Font font, float size, float width, String raw) {
            String text = f.safe(font, raw == null ? "" : raw);
            List<String> out = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (String word : text.split(" ", -1)) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (f.width(font, size, candidate) <= width) {
                    current.setLength(0);
                    current.append(candidate);
                    continue;
                }
                if (!current.isEmpty()) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                // A word wider than the column is broken by characters.
                String rest = word;
                while (f.width(font, size, rest) > width && rest.length() > 1) {
                    int cut = rest.length() - 1;
                    while (cut > 1 && f.width(font, size, rest.substring(0, cut)) > width) cut--;
                    out.add(rest.substring(0, cut));
                    rest = rest.substring(cut);
                }
                current.append(rest);
            }
            if (!current.isEmpty() || out.isEmpty()) out.add(current.toString());
            return out;
        }

        private List<List<Run>> wrapRuns(List<Run> runs, float size, float width) {
            List<List<Run>> lines = new ArrayList<>();
            List<Run> line = new ArrayList<>();
            float used = 0f;
            for (Run run : runs) {
                String[] words = f.safe(run.font(), run.text()).split("(?<= )");
                for (String w : words) {
                    float ww = f.width(run.font(), size, w);
                    if (used + f.width(run.font(), size, w.stripTrailing()) > width && !line.isEmpty()) {
                        lines.add(line);
                        line = new ArrayList<>();
                        used = 0f;
                        w = w.stripLeading();
                        ww = f.width(run.font(), size, w);
                    }
                    if (!line.isEmpty() && line.get(line.size() - 1).font() == run.font()) {
                        Run last = line.remove(line.size() - 1);
                        line.add(new Run(run.font(), last.text() + w));
                    } else {
                        line.add(new Run(run.font(), w));
                    }
                    used += ww;
                }
            }
            if (!line.isEmpty()) lines.add(line);
            return lines;
        }
    }
}
