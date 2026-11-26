package com.securedoc.platform.infrastructure.extraction;

import com.securedoc.platform.application.port.out.TextExtractionPort.PageText;
import com.securedoc.platform.application.port.out.TextExtractionPort.TextSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Task 0099 (R3): a page read by OCR can still carry text of its own that OCR does not see: white text, text of one
 * point, text under a picture. It stays in the output when the page has nothing to cover, so it is searched like the
 * rest. Every line of the page's own text with a word that OCR did not read at that place is added after the OCR text,
 * whole (so its label stays with its value), each character at its own place. Lines OCR read are not added twice.
 * In memory only; nothing is kept or logged.
 */
final class HiddenNativeText {

    /** OCR read a word when the text it read over the word's place has this share of the word's letters, in order. */
    static final double READ = 0.6;
    /** An OCR word stands over a native word when they share this much of the smaller box. */
    private static final double OVER = 0.3;

    private HiddenNativeText() {
    }

    static PageText with(PageText ocr, PageText nativePage) {
        if (nativePage == null || nativePage.text().isBlank()) return ocr;
        String text = nativePage.text();
        StringBuilder all = null;
        List<TextSpan> spans = null;
        int lineStart = 0;
        while (lineStart < text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = text.length();
            if (hasUnreadWord(ocr, nativePage, lineStart, lineEnd)) {
                if (all == null) {
                    all = new StringBuilder(ocr.text());
                    spans = new ArrayList<>(ocr.spans());
                }
                if (!all.isEmpty() && all.charAt(all.length() - 1) != '\n') all.append('\n');
                int shift = all.length() - lineStart;
                all.append(text, lineStart, lineEnd);
                for (TextSpan span : nativePage.spans()) {
                    if (span.startOffset() >= lineStart && span.endOffset() <= lineEnd) {
                        spans.add(new TextSpan(span.startOffset() + shift, span.endOffset() + shift, span.left(),
                                span.top(), span.width(), span.height(), span.confidence()));
                    }
                }
                all.append('\n');
            }
            lineStart = lineEnd + 1;
        }
        return all == null ? ocr : new PageText(ocr.pageNumber(), ocr.width(), ocr.height(), all.toString(), spans,
                ocr.visualRegions(), ocr.source(), ocr.sourceConfidence());
    }

    private static boolean hasUnreadWord(PageText ocr, PageText nativePage, int lineStart, int lineEnd) {
        String text = nativePage.text();
        int wordStart = lineStart;
        while (wordStart < lineEnd) {
            while (wordStart < lineEnd && Character.isWhitespace(text.charAt(wordStart))) wordStart++;
            int wordEnd = wordStart;
            while (wordEnd < lineEnd && !Character.isWhitespace(text.charAt(wordEnd))) wordEnd++;
            if (wordEnd > wordStart && !readByOcr(ocr, nativePage, wordStart, wordEnd)) return true;
            wordStart = wordEnd;
        }
        return false;
    }

    private static boolean readByOcr(PageText ocr, PageText nativePage, int start, int end) {
        String word = letters(nativePage.text().substring(start, end));
        if (word.isEmpty()) return true; // punctuation only: nothing to find in it
        double[] box = box(nativePage.spans(), start, end);
        if (box == null) return false; // no place: OCR can't have read it
        StringBuilder over = new StringBuilder();
        boolean any = false;
        for (TextSpan span : ocr.spans()) {
            if (span.endOffset() > ocr.text().length()) continue;
            if (shared(box, span) >= OVER) {
                any = true;
                over.append(ocr.text(), span.startOffset(), span.endOffset());
            }
        }
        // One character ("I" read as "|"): read when OCR read anything at its place.
        if (word.length() == 1) return any;
        return inOrder(word, letters(over.toString())) >= READ * word.length();
    }

    private static double[] box(List<TextSpan> spans, int start, int end) {
        double left = Double.MAX_VALUE, top = Double.MAX_VALUE, right = -1, bottom = -1;
        for (TextSpan span : spans) {
            if (span.startOffset() < start || span.endOffset() > end) continue;
            left = Math.min(left, span.left());
            top = Math.min(top, span.top());
            right = Math.max(right, span.left() + span.width());
            bottom = Math.max(bottom, span.top() + span.height());
        }
        return right < 0 ? null : new double[]{left, top, right, bottom};
    }

    /** The shared area as a share of the smaller box. */
    private static double shared(double[] box, TextSpan span) {
        double w = Math.min(box[2], span.left() + span.width()) - Math.max(box[0], span.left());
        double h = Math.min(box[3], span.top() + span.height()) - Math.max(box[1], span.top());
        if (w <= 0 || h <= 0) return 0;
        double smaller = Math.min((box[2] - box[0]) * (box[3] - box[1]), span.width() * span.height());
        return smaller <= 0 ? 0 : w * h / smaller;
    }

    private static String letters(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit).forEach(out::appendCodePoint);
        return out.toString();
    }

    /** The longest run of {@code a}'s characters found in order in {@code b}. */
    static int inOrder(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                current[j] = a.charAt(i - 1) == b.charAt(j - 1) ? previous[j - 1] + 1
                        : Math.max(previous[j], current[j - 1]);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
