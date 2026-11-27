package com.securedoc.platform.infrastructure.extraction;

import com.securedoc.platform.application.port.out.TextExtractionPort.PageText;
import com.securedoc.platform.application.port.out.TextExtractionPort.TextSpan;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Task 0053: the text of a page's visible form fields (text, combo box and list fields), which is not part of the page's
 * own drawing. It is added after the page's text, one line per field: its label (words only, without a place), then its
 * value, whose place is the field's box. So a value is found and covered like page text, and every offset of the page's
 * own text stays as it was. A page without such fields is returned unchanged. Addendum: so is the text of every other
 * annotation the redaction draws into the page (free-text notes, stamps, callouts, button captions, signatures).
 *
 * <p>The value is the field's stored value and, where the drawn text differs, the drawn text too. Check boxes and
 * radio buttons have no text and are ignored.
 */
final class PdfFormFieldText {

    private static final int PUSH_BUTTON = 1 << 16;

    private PdfFormFieldText() {
    }

    static PageText with(PDDocument document, PDPage page, PageText text) throws IOException {
        StringBuilder all = null;
        List<TextSpan> spans = null;
        for (PDAnnotation annotation : page.getAnnotations()) {
            // The same annotations the redaction draws into the page (PdfOutputCleaner).
            if (annotation instanceof PDAnnotationPopup || annotation instanceof PDAnnotationLink
                    || annotation.isHidden() || annotation.isNoView()) continue;
            String type = inherited(annotation.getCOSObject(), COSName.FT) instanceof COSName name ? name.getName() : "";
            boolean field = type.equals("Tx") || type.equals("Ch");
            // A check box or radio button draws a mark, not text; a push button's caption is text.
            if (type.equals("Btn") && (flags(annotation) & PUSH_BUTTON) == 0) continue;
            double[] place = place(page, annotation.getRectangle());
            if (place == null) continue;
            Set<String> values = field ? values(annotation) : drawnOnly(document, annotation);
            if (values.isEmpty()) continue;
            if (all == null) {
                all = new StringBuilder(text.text());
                spans = new ArrayList<>(text.spans());
            }
            String label = label(page, annotation, text, place);
            for (String value : values) {
                if (!all.isEmpty() && all.charAt(all.length() - 1) != '\n') all.append('\n');
                if (!label.isEmpty()) all.append(label).append(": ");
                int start = all.length();
                all.append(value);
                spans.add(new TextSpan(start, all.length(), place[0], place[1], place[2], place[3], 1.0, true));
                all.append('\n');
            }
        }
        return all == null ? text : new PageText(text.pageNumber(), text.width(), text.height(), all.toString(), spans,
                text.visualRegions(), text.source(), text.sourceConfidence());
    }

    /** Task 0053, addendum: a note, stamp, callout, button caption or signature says what it draws. */
    private static Set<String> drawnOnly(PDDocument document, PDAnnotation annotation) throws IOException {
        if (annotation.getNormalAppearanceStream() == null) {
            try {
                // As the redaction does: an annotation without a stored look gets its standard one.
                annotation.constructAppearances(document);
            } catch (RuntimeException noLook) {
                return Set.of();
            }
        }
        String drawn = drawn(annotation);
        return drawn.isEmpty() ? Set.of() : Set.of(drawn);
    }

    private static int flags(PDAnnotation annotation) {
        return inherited(annotation.getCOSObject(), COSName.FF) instanceof COSNumber number ? number.intValue() : 0;
    }

    /** The stored value (with a choice's shown words) and the drawn text, each once. */
    private static Set<String> values(PDAnnotation widget) throws IOException {
        Set<String> values = new LinkedHashSet<>();
        COSBase stored = inherited(widget.getCOSObject(), COSName.V);
        collect(stored, values);
        // A choice is stored by its export value; what the page shows is the option's words.
        if (inherited(widget.getCOSObject(), COSName.OPT) instanceof COSArray options) {
            for (int i = 0; i < options.size(); i++) {
                if (options.getObject(i) instanceof COSArray pair && pair.size() == 2
                        && pair.getObject(0) instanceof COSString export && pair.getObject(1) instanceof COSString shown
                        && values.contains(line(export.getString()))) {
                    collect(shown, values);
                }
            }
        }
        String drawn = drawn(widget);
        String compared = comparable(drawn);
        if (!compared.isEmpty() && values.stream().noneMatch(v -> comparable(v).contains(compared))) values.add(drawn);
        return values;
    }

    private static void collect(COSBase base, Set<String> values) throws IOException {
        if (base instanceof COSString string) add(string.getString(), values);
        else if (base instanceof COSStream stream) {
            // A long or rich value can be a stream.
            try (var in = stream.createInputStream()) {
                add(new COSString(in.readAllBytes()).getString(), values);
            }
        } else if (base instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) collect(array.getObject(i), values);
        }
    }

    private static void add(String value, Set<String> values) {
        String line = line(value);
        if (!line.isEmpty()) values.add(line);
    }

    private static String line(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    /** The text of the field's appearance, read from a page of its own. */
    private static String drawn(PDAnnotation widget) throws IOException {
        PDAppearanceStream appearance = widget.getNormalAppearanceStream();
        PDRectangle box = appearance == null ? null : appearance.getBBox();
        if (box == null || box.getWidth() <= 0 || box.getHeight() <= 0) return "";
        try (PDDocument own = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(box.getLowerLeftX(), box.getLowerLeftY(), box.getWidth(),
                    box.getHeight()));
            own.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(own, page)) {
                content.drawForm(new PDFormXObject(appearance.getCOSObject()));
            }
            return line(new PDFTextStripper().getText(own));
        }
    }

    /**
     * What the field is for: its description, its name, and the page's words just left of it or just above it. Words
     * with a digit are left out: a label has no place, so a value must never be in it.
     */
    private static String label(PDPage page, PDAnnotation widget, PageText text, double[] place) {
        Set<String> words = new LinkedHashSet<>();
        for (COSName key : List.of(COSName.TU, COSName.T)) {
            if (inherited(widget.getCOSObject(), key) instanceof COSString name) addWords(name.getString(), words);
        }
        double middle = place[1] + place[3] / 2;
        StringBuilder left = new StringBuilder();
        StringBuilder above = new StringBuilder();
        int end = -1;
        for (TextSpan span : text.spans()) {
            double spanMiddle = span.top() + span.height() / 2;
            boolean besides = Math.abs(spanMiddle - middle) <= Math.max(place[3], span.height()) / 2
                    && span.left() + span.width() <= place[0] + 0.01 && span.left() >= place[0] - 0.35;
            boolean over = span.top() + span.height() <= place[1] + 0.01 && span.top() >= place[1] - 0.03
                    && span.left() < place[0] + place[2] && span.left() + span.width() > place[0];
            if (!besides && !over) continue;
            StringBuilder to = besides ? left : above;
            if (span.endOffset() > text.text().length()) continue;
            if (end >= 0 && span.startOffset() != end) to.append(' ');
            to.append(text.text(), span.startOffset(), span.endOffset());
            end = span.endOffset();
        }
        addWords(left.toString(), words);
        if (left.isEmpty()) addWords(above.toString(), words);
        return String.join(" ", words);
    }

    private static void addWords(String text, Set<String> words) {
        for (String word : text.split("[^\\p{L}\\p{N}']+")) {
            if (word.length() > 1 && word.codePoints().noneMatch(Character::isDigit)) words.add(word);
        }
    }

    /** The field's box on the page as it is shown: from the top left, between 0 and 1, turned with the page. */
    private static double[] place(PDPage page, PDRectangle rectangle) {
        if (rectangle == null) return null;
        PDRectangle shown = page.getCropBox();
        double x = (rectangle.getLowerLeftX() - shown.getLowerLeftX()) / shown.getWidth();
        double y = (shown.getUpperRightY() - rectangle.getUpperRightY()) / shown.getHeight();
        double w = rectangle.getWidth() / shown.getWidth();
        double h = rectangle.getHeight() / shown.getHeight();
        double[] turned = switch (Math.floorMod(page.getRotation(), 360)) {
            case 90 -> new double[]{1 - y - h, x, h, w};
            case 180 -> new double[]{1 - x - w, 1 - y - h, w, h};
            case 270 -> new double[]{y, 1 - x - w, h, w};
            default -> new double[]{x, y, w, h};
        };
        double left = clamp(turned[0]);
        double top = clamp(turned[1]);
        double right = clamp(turned[0] + turned[2]);
        double bottom = clamp(turned[1] + turned[3]);
        // Outside the shown page: nobody sees it, and the output does not keep it as a field.
        if (right <= left || bottom <= top) return null;
        return new double[]{left, top, right - left, bottom - top};
    }

    /** A field's entry can be on a field above the shown widget. */
    private static COSBase inherited(COSDictionary field, COSName key) {
        COSDictionary at = field;
        for (int depth = 0; at != null && depth < 32; depth++) {
            COSBase value = at.getDictionaryObject(key);
            if (value != null) return value;
            at = at.getDictionaryObject(COSName.PARENT) instanceof COSDictionary parent ? parent : null;
        }
        return null;
    }

    private static String comparable(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().filter(Character::isLetterOrDigit).map(Character::toLowerCase).forEach(out::appendCodePoint);
        return out.toString();
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
