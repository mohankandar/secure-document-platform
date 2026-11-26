package com.securedoc.platform.infrastructure.extraction;

import com.securedoc.platform.application.port.out.TextExtractionPort.ExtractionSource;
import com.securedoc.platform.application.port.out.TextExtractionPort.PageText;
import com.securedoc.platform.application.port.out.TextExtractionPort.TextSpan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0099 (R3): a page read by OCR gets the lines of its own text that OCR did not read (white, one point, under a
 * picture), each character at its own place; lines OCR read are not added again. Fake values only.
 */
class HiddenNativeTextTest {

    @Test
    void linesOcrDidNotReadAreAddedWithTheirPlaces() {
        PageText nativePage = nativePage(List.of(
                new Line("The appraised value is $455,000.", 0.10),
                new Line("Hidden: borrower SSN 512-34-8876", 0.30),
                new Line("tiny text DOB 03/14/1979", 0.50)));
        PageText ocr = ocrPage(List.of(new Line("The appraised value is $455,000.", 0.10)));

        PageText merged = HiddenNativeText.with(ocr, nativePage);

        assertEquals("The appraised value is $455,000.\nHidden: borrower SSN 512-34-8876\ntiny text DOB 03/14/1979\n",
                merged.text());
        assertEquals(ExtractionSource.OCR, merged.source());
        int at = merged.text().indexOf("512-34-8876");
        TextSpan first = merged.spans().stream().filter(s -> s.startOffset() == at).findFirst().orElseThrow();
        TextSpan same = nativePage.spans().stream()
                .filter(s -> s.startOffset() == nativePage.text().indexOf("512-34-8876")).findFirst().orElseThrow();
        assertEquals(same.left(), first.left());
        assertEquals(same.top(), first.top());
    }

    @Test
    void aPageOcrReadInFullIsUnchanged() {
        List<Line> lines = List.of(new Line("Borrower SSN 512-34-8876", 0.10), new Line("Page 1 of 1", 0.20));
        PageText ocr = ocrPage(lines);
        assertSame(ocr, HiddenNativeText.with(ocr, nativePage(lines)));
        assertSame(ocr, HiddenNativeText.with(ocr, null));
    }

    @Test
    void ocrMisreadingAFewLettersStillCountsAsRead() {
        PageText ocr = ocrPage(List.of(new Line("| certify the inforrnation above is true.", 0.10)));
        PageText nativePage = nativePage(List.of(new Line("I certify the information above is true.", 0.10)));
        assertSame(ocr, HiddenNativeText.with(ocr, nativePage));
    }

    @Test
    void otherTextOverAWordIsNotReadingIt() {
        // White text drawn under a visible line: OCR read the visible words at that place, not the hidden ones.
        PageText ocr = ocrPage(List.of(new Line("Nothing to see here at all", 0.10)));
        PageText nativePage = nativePage(List.of(new Line("Card 4111111111111111 SSN", 0.10)));
        assertTrue(HiddenNativeText.with(ocr, nativePage).text().contains("Card 4111111111111111 SSN"));
    }

    @Test
    void inOrder() {
        assertEquals(3, HiddenNativeText.inOrder("abc", "xaybzc"));
        assertEquals(0, HiddenNativeText.inOrder("abc", ""));
    }

    // ------------------------------------------------------------------ helpers

    private record Line(String text, double top) { }

    /** Native text: one span per character, 0.01 wide. */
    private static PageText nativePage(List<Line> lines) {
        StringBuilder text = new StringBuilder();
        List<TextSpan> spans = new ArrayList<>();
        for (Line line : lines) {
            for (int i = 0; i < line.text().length(); i++) {
                int at = text.length();
                text.append(line.text().charAt(i));
                if (line.text().charAt(i) != ' ') spans.add(new TextSpan(at, at + 1, 0.1 + i * 0.01, line.top(), 0.01, 0.02));
            }
            text.append('\n');
        }
        return new PageText(1, 612, 792, text.toString(), spans, ExtractionSource.NATIVE_PDF_TEXT, 1.0);
    }

    /** OCR text: one span per word, at the same places as the native characters. */
    private static PageText ocrPage(List<Line> lines) {
        StringBuilder text = new StringBuilder();
        List<TextSpan> spans = new ArrayList<>();
        for (Line line : lines) {
            int i = 0;
            for (String word : line.text().split(" ")) {
                int at = text.length();
                text.append(word);
                spans.add(new TextSpan(at, text.length(), 0.1 + i * 0.01, line.top(), word.length() * 0.01, 0.02, 0.9));
                text.append(' ');
                i += word.length() + 1;
            }
            text.setLength(text.length() - 1);
            text.append('\n');
        }
        return new PageText(1, 612, 792, text.toString().strip(), spans, ExtractionSource.OCR, 0.9);
    }
}
