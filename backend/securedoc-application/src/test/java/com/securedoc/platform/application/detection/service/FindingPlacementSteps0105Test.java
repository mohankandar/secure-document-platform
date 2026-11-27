package com.securedoc.platform.application.detection.service;

import com.securedoc.platform.application.port.out.TextExtractionPort.ExtractionSource;
import com.securedoc.platform.application.port.out.TextExtractionPort.PageText;
import com.securedoc.platform.application.port.out.TextExtractionPort.TextSpan;
import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;
import com.securedoc.platform.domain.finding.FindingPlacement;
import com.securedoc.platform.domain.finding.FindingPlacement.How;
import com.securedoc.platform.domain.finding.FindingPlacement.Method;
import com.securedoc.platform.domain.finding.FindingPlacement.Reader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0105: each placement step on its own. Made-up values only. */
class FindingPlacementSteps0105Test {

    // ---------------------------------------------------------------------------------------- step 2b, matching

    @Test
    void aValueThatStandsTwiceIsPlacedAtBothPlaces() {
        String text = "SSN: 078-05-1120\nSocial Security No.: 078-05-1120\n";
        List<int[]> places = ValuePlaces.of(text, "078-05-1120");
        assertEquals(2, places.size());
        assertEquals("078-05-1120", text.substring(places.get(0)[0], places.get(0)[1]));
        assertEquals("078-05-1120", text.substring(places.get(1)[0], places.get(1)[1]));
    }

    @Test
    void ocrsUsualConfusionsAndSeparatorsAreReadThrough() {
        String text = "DOB  O1 / l5 / 198O  signed";
        List<int[]> places = ValuePlaces.of(text, "01/15/1980");
        assertEquals(1, places.size());
        assertEquals("O1 / l5 / 198O", text.substring(places.get(0)[0], places.get(0)[1]));
        String spaced = "SSN 078 05 1120 end";
        assertEquals("078 05 1120", one(spaced, "078-05-1120"));
    }

    @Test
    void aValueInsideALongerNumberOrAShortValueIsNotMatched() {
        assertTrue(ValuePlaces.of("Account 99078051120 open", "078-05-1120").isEmpty());
        assertTrue(ValuePlaces.of("Ref 12 3", "123").isEmpty(), "too short to place by matching");
        assertTrue(ValuePlaces.of("Name: Ada Lovelace", "Ada  Lovelace").isEmpty(), "words without digits: exact only");
    }

    private static String one(String text, String value) {
        List<int[]> places = ValuePlaces.of(text, value);
        assertEquals(1, places.size(), text);
        return text.substring(places.get(0)[0], places.get(0)[1]);
    }

    // ---------------------------------------------------------------------------------------- step 2c, the field area

    @Test
    void theFieldAreaIsRightOfTheLabelOrBelowItAtTheRightEdge() {
        BoundingBox right = FindingPlacements.fieldArea(BoundingBox.of(0.10, 0.30, 0.10, 0.02));
        assertEquals(0.20, right.left(), 1e-9);
        assertTrue(right.width() > 0.3 && right.left() + right.width() <= 1.0);
        assertTrue(right.top() < 0.30 && right.top() + right.height() > 0.32, "covers the label's line");
        BoundingBox below = FindingPlacements.fieldArea(BoundingBox.of(0.85, 0.30, 0.10, 0.02));
        assertEquals(0.85, below.left(), 1e-9);
        assertEquals(0.32, below.top(), 1e-9);
        assertTrue(below.left() + below.width() <= 1.0);
    }

    @Test
    void anUnsureOcrFieldIsPlacedAtItsValueElseBesideItsLabelElseNotAtAll() {
        String text = "Date of Birth: 12/O3/1961";
        PageText page = new PageText(1, 1000, 1000, text, List.of(
                new TextSpan(0, 13, 0.10, 0.30, 0.15, 0.02, 0.9),
                new TextSpan(15, 25, 0.30, 0.30, 0.12, 0.02, 0.4)), List.of(), ExtractionSource.OCR, 0.6);
        FindingPlacements.Placed value = FindingPlacements.ofUnsureField(page, 1, 15, 25, 0, 13);
        assertTrue(value.location().hasBoundingBox());
        assertEquals(How.READER_WORDS, value.placement().how());
        assertEquals(FindingPlacements.UNSURE_FIELD, value.placement().reason());

        FindingPlacements.Placed label = FindingPlacements.ofUnsureField(page, 1, -1, -1, 0, 13);
        assertTrue(label.location().hasBoundingBox());
        assertEquals(How.LABEL_FIELD_AREA, label.placement().how());
        assertEquals(FindingPlacement.LABEL_WITHOUT_VALUE, label.placement().reason());

        FindingPlacements.Placed none = FindingPlacements.ofUnsureField(page, 1, -1, -1, -1, -1);
        assertFalse(none.location().hasBoundingBox());
        assertEquals(How.NONE, none.placement().how());
        assertEquals(FindingPlacement.NO_PLACE, none.placement().reason());
        for (var p : List.of(value, label, none)) assertEquals(Reader.OCR, p.placement().reader());
    }

    // ---------------------------------------------------------------------------------------- the reader

    @Test
    void theReaderIsTheFormFieldOcrOrThePdfsOwnText() {
        String text = "Applicant\nSSN: 078-05-1120\n";
        List<TextSpan> spans = List.of(new TextSpan(0, 9, 0.1, 0.1, 0.2, 0.02),
                new TextSpan(15, 26, 0.5, 0.4, 0.2, 0.03, 1.0, true));
        PageText nativePage = new PageText(1, 600, 800, text, spans, List.of(), ExtractionSource.NATIVE_PDF_TEXT, 1.0);
        assertEquals(Reader.FORM_FIELD, FindingPlacements.reader(nativePage, 15, 26));
        assertEquals(Reader.PDF_TEXT, FindingPlacements.reader(nativePage, 0, 9));
        PageText ocrPage = new PageText(1, 600, 800, text, List.of(spans.get(0)), List.of(), ExtractionSource.OCR, 0.8);
        assertEquals(Reader.OCR, FindingPlacements.reader(ocrPage, 0, 9));

        FindingPlacement placed = FindingPlacements.ofText(nativePage, 15, 26, true, true, true);
        assertEquals(new FindingPlacement(Reader.FORM_FIELD, Method.AI_MODEL, How.MATCHED_WORDS, null, null, null), placed);
        FindingPlacement unplaced = FindingPlacements.ofText(ocrPage, 0, 9, false, false, false);
        assertEquals(How.NONE, unplaced.how());
        assertEquals(FindingPlacement.NO_PLACE, unplaced.reason());
    }

    // ---------------------------------------------------------------------------------------- step 2a, the model's box

    @Test
    void theModelsBoxIsItsPlaceWhenItHasASizeAValueCanHave() {
        assertEquals(How.MODEL_BOX, VisionFindings.modelBox(BoundingBox.of(0.2, 0.2, 0.15, 0.03), false).how());
        assertNull(VisionFindings.modelBox(BoundingBox.of(0.2, 0.2, 0.15, 0.03), false).reason());
        assertEquals(VisionFindings.IMPLAUSIBLE_BOX, VisionFindings.modelBox(BoundingBox.of(0, 0, 0.9, 0.9), false).reason());
        assertEquals(VisionFindings.IMPLAUSIBLE_BOX, VisionFindings.modelBox(BoundingBox.of(0.2, 0.2, 0.0005, 0.02), false).reason());
        assertEquals(Method.PATTERN, VisionFindings.modelBox(BoundingBox.of(0.2, 0.2, 0.15, 0.03), true).method());
    }
}
