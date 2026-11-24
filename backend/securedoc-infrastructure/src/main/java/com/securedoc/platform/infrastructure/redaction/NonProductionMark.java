package com.securedoc.platform.infrastructure.redaction;

import com.securedoc.platform.application.port.out.PdfRedactionPort.RedactionTarget;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Task 0057: the mark on every page of an output made under a licence for tests ("TEST – NOT FOR PRODUCTION USE", diagonal),
 * and the flag in the file. Never drawn for any other licence.
 *
 * <p>It is a drawing, not text: strokes of a small alphabet of its own, so nothing is added to what can be searched, copied
 * or read, and every server draws the same. It is clipped out of every redaction box (a little wider than the box), so it
 * never covers one, whatever the boxes overlap. It is half see-through, so the page under it stays readable.
 */
public final class NonProductionMark {

    static final String TEXT = "TEST – NOT FOR PRODUCTION USE";
    /** In the file's information: true on a marked output. */
    static final COSName FLAG = COSName.getPDFName("NonProduction");

    private static final float LENGTH_OF_DIAGONAL = 0.8f;
    private static final float ADVANCE = 5.6f;
    private static final float HEIGHT = 6f;
    private static final float KEEP_OFF_BOX_POINTS = 2f;

    /** Each letter on a field 4 wide and 6 high: lines of x,y pairs. */
    private static final Map<Character, float[][]> LETTERS = Map.ofEntries(
            Map.entry('C', new float[][] {{4, 5, 3, 6, 1, 6, 0, 5, 0, 1, 1, 0, 3, 0, 4, 1}}),
            Map.entry('D', new float[][] {{0, 0, 0, 6, 2.5f, 6, 4, 4.5f, 4, 1.5f, 2.5f, 0, 0, 0}}),
            Map.entry('E', new float[][] {{4, 6, 0, 6, 0, 0, 4, 0}, {0, 3, 3, 3}}),
            Map.entry('F', new float[][] {{4, 6, 0, 6, 0, 0}, {0, 3, 3, 3}}),
            Map.entry('I', new float[][] {{2, 0, 2, 6}}),
            Map.entry('N', new float[][] {{0, 0, 0, 6, 4, 0, 4, 6}}),
            Map.entry('O', new float[][] {{1, 0, 3, 0, 4, 1, 4, 5, 3, 6, 1, 6, 0, 5, 0, 1, 1, 0}}),
            Map.entry('P', new float[][] {{0, 0, 0, 6, 3, 6, 4, 5, 4, 4, 3, 3, 0, 3}}),
            Map.entry('R', new float[][] {{0, 0, 0, 6, 3, 6, 4, 5, 4, 4, 3, 3, 0, 3}, {2, 3, 4, 0}}),
            Map.entry('S', new float[][] {{4, 5, 3, 6, 1, 6, 0, 5, 0, 4, 1, 3, 3, 3, 4, 2, 4, 1, 3, 0, 1, 0, 0, 1}}),
            Map.entry('T', new float[][] {{0, 6, 4, 6}, {2, 6, 2, 0}}),
            Map.entry('U', new float[][] {{0, 6, 0, 1, 1, 0, 3, 0, 4, 1, 4, 6}}),
            Map.entry('–', new float[][] {{0.5f, 3, 3.5f, 3}}),
            Map.entry(' ', new float[][] {}));

    private NonProductionMark() {
    }

    /** Marks one output page. {@code targets} are the page's redaction boxes (fractions of the page, from its top left). */
    static void draw(PDDocument document, PDPage page, List<RedactionTarget> targets) throws IOException {
        closeOpenStates(document, page);
        PDRectangle shown = page.getCropBox();
        int rotation = Math.floorMod(page.getRotation(), 360);
        boolean sideways = rotation == 90 || rotation == 270;
        float width = sideways ? shown.getHeight() : shown.getWidth();
        float height = sideways ? shown.getWidth() : shown.getHeight();
        double diagonal = Math.hypot(width, height);
        float length = TEXT.length() * ADVANCE - (ADVANCE - 4);
        float scale = (float) (LENGTH_OF_DIAGONAL * diagonal / length);

        try (PDPageContentStream content = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND,
                true, false)) {
            content.saveGraphicsState();
            for (RedactionTarget target : targets) keepOff(content, shown, target);
            PDExtendedGraphicsState seeThrough = new PDExtendedGraphicsState();
            seeThrough.setStrokingAlphaConstant(0.3f);
            content.setGraphicsStateParameters(seeThrough);
            content.setStrokingColor(0.8f, 0.1f, 0.1f);
            content.setLineCapStyle(1);
            content.setLineJoinStyle(1);
            content.setLineWidth(0.8f);
            content.transform(Matrix.getTranslateInstance(shown.getLowerLeftX() + shown.getWidth() / 2,
                    shown.getLowerLeftY() + shown.getHeight() / 2));
            content.transform(Matrix.getRotateInstance(Math.atan2(height, width) + Math.toRadians(rotation), 0, 0));
            content.transform(Matrix.getScaleInstance(scale, scale));
            content.transform(Matrix.getTranslateInstance(-length / 2, -HEIGHT / 2));
            for (int i = 0; i < TEXT.length(); i++) letter(content, TEXT.charAt(i), i * ADVANCE);
            content.stroke();
            content.restoreGraphicsState();
        }
    }

    static void flag(PDDocument document) {
        document.getDocumentInformation().getCOSObject().setBoolean(FLAG, true);
    }

    /** A file made from a marked one is marked as well (its pages already are). */
    public static void carry(PDDocument from, PDDocument to) {
        if (from.getDocumentInformation().getCOSObject().getBoolean(FLAG, false)) flag(to);
    }

    /** Nothing is drawn inside the box: the page without it becomes the place to draw in. Each box narrows it further. */
    private static void keepOff(PDPageContentStream content, PDRectangle shown, RedactionTarget target) throws IOException {
        float left = (float) (shown.getLowerLeftX() + target.left() * shown.getWidth()) - KEEP_OFF_BOX_POINTS;
        float bottom = (float) (shown.getUpperRightY() - (target.top() + target.height()) * shown.getHeight())
                - KEEP_OFF_BOX_POINTS;
        content.addRect(shown.getLowerLeftX() - 1000, shown.getLowerLeftY() - 1000, shown.getWidth() + 2000,
                shown.getHeight() + 2000);
        content.addRect(left, bottom, (float) (target.width() * shown.getWidth()) + 2 * KEEP_OFF_BOX_POINTS,
                (float) (target.height() * shown.getHeight()) + 2 * KEEP_OFF_BOX_POINTS);
        content.clipEvenOdd();
    }

    private static void letter(PDPageContentStream content, char letter, float at) throws IOException {
        for (float[] line : LETTERS.get(letter)) {
            content.moveTo(at + line[0], line[1]);
            for (int i = 2; i < line.length; i += 2) content.lineTo(at + line[i], line[i + 1]);
        }
    }

    /** The page's own content may leave drawing states open, or close too many: the mark starts from the page's own state. */
    private static void closeOpenStates(PDDocument document, PDPage page) throws IOException {
        Iterator<PDStream> own = page.getContentStreams();
        List<PDStream> streams = new ArrayList<>();
        while (own.hasNext()) streams.add(own.next());
        if (streams.isEmpty()) return;
        int depth = 0;
        int lowest = 0;
        try (var content = page.getContents()) {
            for (Object token : new PDFStreamParser(content.readAllBytes()).parse()) {
                if (!(token instanceof Operator operator)) continue;
                if (operator.getName().equals("q")) depth++;
                if (operator.getName().equals("Q")) lowest = Math.min(lowest, --depth);
            }
        }
        int before = 1 - lowest;
        streams.add(0, operators(document, "q\n".repeat(before)));
        streams.add(operators(document, "\nQ".repeat(before + depth) + "\n"));
        page.setContents(streams);
    }

    private static PDStream operators(PDDocument document, String text) throws IOException {
        PDStream stream = new PDStream(document);
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            out.write(text.getBytes(StandardCharsets.US_ASCII));
        }
        return stream;
    }
}
