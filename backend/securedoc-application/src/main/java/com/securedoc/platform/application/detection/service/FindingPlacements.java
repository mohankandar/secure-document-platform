package com.securedoc.platform.application.detection.service;

import com.securedoc.platform.application.port.out.TextExtractionPort.ExtractionSource;
import com.securedoc.platform.application.port.out.TextExtractionPort.PageText;
import com.securedoc.platform.application.port.out.TextExtractionPort.TextSpan;
import com.securedoc.platform.domain.finding.DetectionPath;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingPlacement;
import com.securedoc.platform.domain.finding.FindingPlacement.How;
import com.securedoc.platform.domain.finding.FindingPlacement.Method;
import com.securedoc.platform.domain.finding.FindingPlacement.Reader;

/**
 * Task 0105: what read a finding, how it was found and how it was placed, for every finding the search makes. A finding
 * never leaves the search without a reader.
 */
final class FindingPlacements {

    private FindingPlacements() {
    }

    /** What read the words at [start, end): a form field or annotation, OCR, or the PDF's own text. */
    static Reader reader(PageText page, int start, int end) {
        if (page == null) return Reader.PDF_TEXT;
        for (TextSpan span : page.spans()) {
            if (span.fromField() && span.startOffset() < end && span.endOffset() > start) return Reader.FORM_FIELD;
        }
        return page.source() == ExtractionSource.OCR ? Reader.OCR : Reader.PDF_TEXT;
    }

    /** A page's reader when no words are known. */
    static Reader reader(PageText page) {
        return page != null && page.source() == ExtractionSource.OCR ? Reader.OCR : Reader.PDF_TEXT;
    }

    /** A finding of the page's text: placed by the reader's own words, by matching, or not at all. */
    static FindingPlacement ofText(PageText page, int start, int end, boolean byModel, boolean hasBox, boolean matched) {
        Reader reader = reader(page, start, end);
        Method method = byModel ? Method.AI_MODEL : Method.PATTERN;
        if (!hasBox) return FindingPlacement.forPerson(reader, method, How.NONE, FindingPlacement.NO_PLACE);
        return FindingPlacement.automatic(reader, method, matched ? How.MATCHED_WORDS : How.READER_WORDS);
    }

    /** Why a person checks an OCR field the guard could not settle. Stored; never rename it. */
    static final String UNSURE_FIELD = "OCR_FIELD_UNSURE";

    /** A place and how it was found. */
    record Placed(FindingLocation location, FindingPlacement placement) { }

    /**
     * A sensitive field OCR read but could not settle: covered at the value's own words (step 2b), else at the field area
     * right of its label, or below it when the label ends at the page's right edge (step 2c). It still goes to a person,
     * with the reason. Without either place, a person places it (step 2d).
     */
    static Placed ofUnsureField(PageText page, int pageNumber, int valueStart, int valueEnd, int labelStart, int labelEnd) {
        if (page != null && valueStart >= 0 && valueEnd > valueStart) {
            var box = FindingLocationMapper.resolveBoundingBox(page, valueStart, valueEnd);
            if (box.isPresent()) {
                return new Placed(FindingLocation.withBounds(pageNumber, valueStart, valueEnd, box.get()),
                        FindingPlacement.forPerson(Reader.OCR, Method.PATTERN, How.READER_WORDS, UNSURE_FIELD));
            }
        }
        if (page != null && labelStart >= 0 && labelEnd > labelStart) {
            var label = FindingLocationMapper.resolveBoundingBox(page, labelStart, labelEnd);
            if (label.isPresent()) {
                return new Placed(FindingLocation.withBounds(pageNumber, 0, 0, fieldArea(label.get())),
                        FindingPlacement.forPerson(Reader.OCR, Method.PATTERN, How.LABEL_FIELD_AREA,
                                FindingPlacement.LABEL_WITHOUT_VALUE));
            }
        }
        return new Placed(FindingLocation.unlocated(pageNumber),
                FindingPlacement.forPerson(Reader.OCR, Method.PATTERN, How.NONE, FindingPlacement.NO_PLACE));
    }

    /** How far right of its label a form's value may stand, as a share of the page. */
    static final double FIELD_WIDTH = 0.45;
    /** A field line is a little taller than its label's words. */
    static final double FIELD_HEIGHT_FACTOR = 1.8;

    /** The form line a label's value is written on: to its right, or below it when there is no room to the right. */
    static FindingLocation.BoundingBox fieldArea(FindingLocation.BoundingBox label) {
        double height = Math.min(1, label.height() * FIELD_HEIGHT_FACTOR);
        double right = label.left() + label.width();
        double room = 0.98 - right;
        if (room >= 0.08) {
            double top = Math.max(0, label.top() + label.height() / 2 - height / 2);
            return clamped(right, top, Math.min(FIELD_WIDTH, room), height);
        }
        return clamped(label.left(), label.top() + label.height(), Math.min(FIELD_WIDTH, 0.98 - label.left()), height);
    }

    private static FindingLocation.BoundingBox clamped(double left, double top, double width, double height) {
        double l = Math.max(0, Math.min(1, left));
        double t = Math.max(0, Math.min(1, top));
        double w = Math.max(0, Math.min(width, 1 - l));
        double h = Math.max(0, Math.min(height, 1 - t));
        return FindingLocation.BoundingBox.of(l, t, w, h);
    }

    /** For a finding that reached the save without one: from what is known of it, never empty. */
    static FindingPlacement fallback(Finding finding) {
        var paths = finding.origin().paths();
        boolean box = finding.location().hasBoundingBox();
        Method method = finding.origin().models().isEmpty() ? Method.PATTERN : Method.AI_MODEL;
        if (paths.contains(DetectionPath.PAGE_CHECK)) {
            return FindingPlacement.forPerson(Reader.PAGE_CHECK, Method.CHECK, How.PAGE,
                    finding.detectorSource().detectorName());
        }
        Reader reader = paths.contains(DetectionPath.TEXT) || paths.isEmpty() ? Reader.PDF_TEXT
                : paths.contains(DetectionPath.VISION) ? Reader.VISION : Reader.SIGNATURE;
        How how = reader == Reader.VISION ? How.MODEL_BOX : How.READER_WORDS;
        return box ? FindingPlacement.automatic(reader, method, how)
                : FindingPlacement.forPerson(reader, method, How.NONE, FindingPlacement.NO_PLACE);
    }
}
