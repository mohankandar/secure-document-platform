package com.securedoc.platform.infrastructure.compliance;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.error.LvWhoActs;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Task 0082: the error code guide as a PDF, for installations without internet. Build-time only (test tree), so it is
 * not in any jar. Every text comes from {@link LvErrorCode}, which reads the customer guide's generated texts.
 * The same texts always give the same bytes. An entry is never split over two pages.
 */
final class ErrorCodeGuidePdf {

    static final String TITLE = "LexaVault error codes";

    static final Map<LvErrorCode.Kind, String> KINDS = Map.of(
            LvErrorCode.Kind.REFUSED, "Refused", LvErrorCode.Kind.WARNING, "Warning",
            LvErrorCode.Kind.WAITING, "Waiting", LvErrorCode.Kind.STOPPED, "Stopped",
            LvErrorCode.Kind.EXPORT_FAILED, "Export failed", LvErrorCode.Kind.NOT_ALLOWED, "Not allowed",
            LvErrorCode.Kind.SET_UP, "Set-up", LvErrorCode.Kind.SYSTEM, "System");

    private static final float W = PDRectangle.LETTER.getWidth();
    private static final float H = PDRectangle.LETTER.getHeight();
    private static final float SIDE = 54f;
    private static final float TOP = H - 54f;
    private static final float BOTTOM = 62f;
    private static final float LABEL_W = 140f;
    private static final float TEXT_X = SIDE + LABEL_W;
    private static final float TEXT_W = W - SIDE - TEXT_X;
    private static final float SIZE = 9.5f;
    private static final float LEAD = 13f;
    private static final Color INK = new Color(0x15, 0x17, 0x1a);
    private static final Color MUTED = new Color(0x62, 0x68, 0x72);
    private static final Color RULE = new Color(0xe1, 0xe3, 0xe6);

    private final PDDocument doc = new PDDocument();
    private final PdfBoxComplianceReportAdapter.Fonts f;
    private final PDDocumentOutline outline = new PDDocumentOutline();
    private PDPageContentStream cs;
    private float y;

    private ErrorCodeGuidePdf() throws IOException {
        f = new PdfBoxComplianceReportAdapter.Fonts(doc);
    }

    static byte[] render() throws IOException {
        ErrorCodeGuidePdf pdf = new ErrorCodeGuidePdf();
        try (PDDocument doc = pdf.doc) {
            return pdf.write();
        }
    }

    /** "User, IT Admin (Source system, IT Admin)": the groups, then the catalogue's own words. */
    static String whoActs(LvErrorCode code) {
        String groups = code.whoActsGroups().stream().map(LvWhoActs::label).collect(Collectors.joining(", "));
        return groups.equals(code.whoActs()) ? groups : groups + " (" + code.whoActs() + ")";
    }

    private byte[] write() throws IOException {
        PDDocumentInformation info = doc.getDocumentInformation();
        info.setTitle(TITLE);
        info.setSubject("What each error code means, who acts, what to do and what support tells the customer");
        info.setCreator("LexaVault");
        info.setProducer("LexaVault");
        doc.getDocumentCatalog().setLanguage("en-US");
        doc.getDocumentCatalog().setDocumentOutline(outline);
        PDViewerPreferences preferences = new PDViewerPreferences(new org.apache.pdfbox.cos.COSDictionary());
        preferences.setDisplayDocTitle(true);
        doc.getDocumentCatalog().setViewerPreferences(preferences);
        doc.setDocumentId(82L);

        PDPage cover = new PDPage(PDRectangle.LETTER);
        doc.addPage(cover);
        Map<LvErrorCode.Area, PDPage> starts = new EnumMap<>(LvErrorCode.Area.class);
        for (LvErrorCode.Area area : LvErrorCode.Area.values()) starts.put(area, area(area));
        cs.close();
        cover(cover, starts);
        int total = doc.getNumberOfPages();
        for (int i = 0; i < total; i++) {
            try (PDPageContentStream s = new PDPageContentStream(doc, doc.getPage(i), PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs = s;
                text(f.sans, 8.25f, MUTED, SIDE, 36f, TITLE);
                String page = "Page " + (i + 1) + " of " + total;
                text(f.sans, 8.25f, MUTED, W - SIDE - f.width(f.sans, 8.25f, page), 36f, page);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    private void cover(PDPage cover, Map<LvErrorCode.Area, PDPage> starts) throws IOException {
        cs = new PDPageContentStream(doc, cover);
        text(f.semibold, 26f, INK, SIDE, TOP - 26f, TITLE);
        y = TOP - 52f;
        for (String paragraph : List.of(
                "Every refusal, stop or warning in LexaVault has a code like LV-INT-001: LV, then the area, then a number. "
                        + "This guide has the same texts as Help › Error codes in LexaVault.",
                "Means says what happened. Who acts is the user, the IT Admin or support, with the exact role in brackets "
                        + "when it is more than that. What to do lists the steps for the person who acts. Support tells the customer "
                        + "is what support says to the person who reports the code. When you ask for help, give the code and the "
                        + "reference number shown with it, and no document content.",
                "Kinds: Refused (nothing taken in or changed), Warning (taken in, something to know), Waiting (not a failure yet), "
                        + "Stopped (taken in, processing stopped), Export failed, Not allowed (a person or client was refused), "
                        + "Set-up (a setting is wrong), System (LexaVault or something it depends on).")) {
            for (String line : wrap(f.sans, 10.5f, W - 2 * SIDE, paragraph)) {
                text(f.sans, 10.5f, INK, SIDE, y, line);
                y -= 14.5f;
            }
            y -= 8f;
        }
        y -= 12f;
        text(f.semibold, 14f, INK, SIDE, y, "Contents");
        y -= 24f;
        for (LvErrorCode.Area area : LvErrorCode.Area.values()) {
            int codes = LvErrorCode.inArea(area).size();
            text(f.mono, 10f, MUTED, SIDE, y, area.name());
            text(f.sans, 10.5f, INK, SIDE + 42f, y, LvErrorCode.areaName(area));
            String count = codes + (codes == 1 ? " code" : " codes");
            text(f.sans, 10.5f, MUTED, SIDE + 260f, y, count);
            String page = String.valueOf(doc.getPages().indexOf(starts.get(area)) + 1);
            text(f.sans, 10.5f, INK, W - SIDE - f.width(f.sans, 10.5f, page), y, page);
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(SIDE, y - 6f, W - 2 * SIDE, 20f));
            PDBorderStyleDictionary none = new PDBorderStyleDictionary();
            none.setWidth(0);
            link.setBorderStyle(none);
            link.setContents(LvErrorCode.areaName(area) + ", page " + page);
            PDActionGoTo go = new PDActionGoTo();
            go.setDestination(at(starts.get(area), H));
            link.setAction(go);
            cover.getAnnotations().add(link);
            rule(y - 7f);
            y -= 22f;
        }
        cs.close();
    }

    private PDPage area(LvErrorCode.Area area) throws IOException {
        newPage();
        PDPage first = doc.getPage(doc.getNumberOfPages() - 1);
        PDOutlineItem item = new PDOutlineItem();
        item.setTitle(LvErrorCode.areaName(area));
        item.setDestination(at(first, H));
        outline.addLast(item);
        text(f.semibold, 18f, INK, SIDE, y - 18f, area.name() + ": " + LvErrorCode.areaName(area));
        y -= 34f;
        for (LvErrorCode code : LvErrorCode.inArea(area)) entry(code, item);
        return first;
    }

    private record Field(String label, List<String> lines) { }

    private void entry(LvErrorCode code, PDOutlineItem areaItem) throws IOException {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("Means", wrap(f.sans, SIZE, TEXT_W, code.description())));
        fields.add(new Field("Kind", List.of(KINDS.get(code.kind()) + (code.reserved() ? ". Reserved: not used yet." : ""))));
        fields.add(new Field("Who acts", wrap(f.sans, SIZE, TEXT_W, whoActs(code))));
        fields.add(new Field("HTTP status", List.of(code.httpStatusLabel())));
        List<String> steps = new ArrayList<>();
        int number = 0;
        for (String step : code.whatToDoSteps()) steps.addAll(wrap(f.sans, SIZE, TEXT_W, ++number + ". " + step));
        fields.add(new Field("What to do", steps));
        fields.add(new Field("Support tells the customer", wrap(f.sans, SIZE, TEXT_W, code.supportTells())));

        List<String> heading = wrap(f.semibold, 12f, W - 2 * SIDE, code.code() + " " + code.title());
        float height = heading.size() * 16f + 26f;
        for (Field field : fields) height += rows(field) * LEAD + 3f;
        if (y - height < BOTTOM) newPage();

        PDOutlineItem item = new PDOutlineItem();
        item.setTitle(code.code() + " " + code.title());
        item.setDestination(at(doc.getPage(doc.getNumberOfPages() - 1), y));
        areaItem.addLast(item);

        rule(y);
        y -= 18f;
        for (String line : heading) {
            text(f.semibold, 12f, INK, SIDE, y, line);
            y -= 16f;
        }
        for (Field field : fields) {
            List<String> label = wrap(f.medium, SIZE, LABEL_W - 10f, field.label());
            for (int i = 0; i < label.size(); i++) text(f.medium, SIZE, MUTED, SIDE, y - i * LEAD, label.get(i));
            for (int i = 0; i < field.lines().size(); i++) text(f.sans, SIZE, INK, TEXT_X, y - i * LEAD, field.lines().get(i));
            y -= rows(field) * LEAD + 3f;
        }
        y -= 8f;
    }

    private int rows(Field field) {
        return Math.max(field.lines().size(), wrap(f.medium, SIZE, LABEL_W - 10f, field.label()).size());
    }

    private void newPage() throws IOException {
        if (cs != null) cs.close();
        PDPage page = new PDPage(PDRectangle.LETTER);
        doc.addPage(page);
        cs = new PDPageContentStream(doc, page);
        y = TOP;
    }

    private static PDPageXYZDestination at(PDPage page, float top) {
        PDPageXYZDestination destination = new PDPageXYZDestination();
        destination.setPage(page);
        destination.setTop(Math.round(top));
        destination.setLeft(0);
        return destination;
    }

    private void rule(float at) throws IOException {
        cs.setStrokingColor(RULE);
        cs.setLineWidth(0.75f);
        cs.moveTo(SIDE, at);
        cs.lineTo(W - SIDE, at);
        cs.stroke();
    }

    private void text(PDType0Font font, float size, Color color, float x, float baseline, String value) throws IOException {
        String safe = f.safe(font, value);
        if (safe.contains("?") && !value.contains("?")) throw new IOException("the font can't draw: " + value);
        cs.beginText();
        cs.setFont(font, size);
        cs.setNonStrokingColor(color);
        cs.newLineAtOffset(x, baseline);
        cs.showText(safe);
        cs.endText();
    }

    private List<String> wrap(PDType0Font font, float size, float width, String value) {
        List<String> lines = new ArrayList<>();
        String line = "";
        for (String word : value.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && f.width(font, size, f.safe(font, next)) > width) {
                lines.add(line);
                line = word;
            } else {
                line = next;
            }
        }
        lines.add(line);
        return lines;
    }
}
