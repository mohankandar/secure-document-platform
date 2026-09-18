package com.securedoc.platform.application.detection.service;

import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.DetectedEntity;
import com.securedoc.platform.application.port.out.TextExtractionPort.ExtractionSource;
import com.securedoc.platform.application.port.out.TextExtractionPort.PageText;
import com.securedoc.platform.application.port.out.TextExtractionPort.TextSpan;
import com.securedoc.platform.application.port.out.TextExtractionPort.VisualRegion;
import com.securedoc.platform.application.port.out.TextExtractionPort.VisualRegionType;
import com.securedoc.platform.domain.finding.EntityType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrSensitiveFieldSafetyPolicyTest {

    private final OcrSensitiveFieldSafetyPolicy policy = new OcrSensitiveFieldSafetyPolicy();
    private final Set<EntityType> enabled = Set.of(
            EntityType.SSN,
            EntityType.DATE_OF_BIRTH,
            EntityType.CREDIT_CARD_NUMBER,
            EntityType.CREDIT_SCORE);

    @Test
    void promotesExplicitOcrCreditScoreToLocatedFinding() {
        String text = "Credit Score: 768\nIssue Date: 04/19/2026";
        var result = policy.evaluate(Map.of(15, ocrPage(15, text)), List.of(), enabled);

        assertEquals(1, result.locatedEntities().size());
        DetectedEntity entity = result.locatedEntities().getFirst();
        assertEquals("CREDIT_SCORE", entity.entityType());
        assertEquals("768", text.substring(entity.startOffset(), entity.endOffset()));
        assertTrue(result.reviewSignals().isEmpty());
    }

    @Test
    void page15ScenarioAddsOnlyMissingScoreWhenOtherOcrFindingsAlreadyExist() {
        String text = String.join("\n",
                "Social Security Number: 987-65-4323",
                "Date of Birth: 02/21/1974",
                "Credit Card Number: 6011 111111111117",
                "Credit Score: 768",
                "Issue Date: 04/19/2026",
                "Reference Number: 402199001177");

        List<DetectedEntity> existing = List.of(
                entity(text, "SSN", "987-65-4323"),
                entity(text, "DATE_OF_BIRTH", "02/21/1974"),
                entity(text, "CREDIT_CARD_NUMBER", "6011 111111111117"));

        var result = policy.evaluate(Map.of(15, ocrPage(15, text)), existing, enabled);

        assertEquals(1, result.locatedEntities().size());
        assertEquals("CREDIT_SCORE", result.locatedEntities().getFirst().entityType());
        assertEquals("768", text.substring(
                result.locatedEntities().getFirst().startOffset(),
                result.locatedEntities().getFirst().endOffset()));
        assertTrue(result.reviewSignals().isEmpty());
    }

    @Test
    void unresolvedOcrSensitiveFieldForcesReviewInsteadOfSilentExport() {
        String text = "Credit Score: 76B";
        var result = policy.evaluate(Map.of(15, ocrPage(15, text)), List.of(), enabled);

        assertTrue(result.locatedEntities().isEmpty());
        assertEquals(1, result.reviewSignals().size());
        assertEquals(EntityType.CREDIT_SCORE, result.reviewSignals().getFirst().entityType());
        assertEquals(15, result.reviewSignals().getFirst().pageNumber());
    }

    @Test
    void spatialFallbackFindsScoreWhenOcrLogicalTextSeparatesLabelAndValue() {
        String text = "Credit Score:\n768\nIssue Date:\n04/19/2026";
        PageText page = spatialPage(text, "768");

        var result = policy.evaluate(Map.of(15, page), List.of(), enabled);

        assertEquals(1, result.locatedEntities().size());
        DetectedEntity entity = result.locatedEntities().getFirst();
        assertEquals("CREDIT_SCORE", entity.entityType());
        assertEquals("768", text.substring(entity.startOffset(), entity.endOffset()));
        assertTrue(result.reviewSignals().isEmpty());
        assertEquals(1, result.spatialScoreCandidateCount());
    }

    @Test
    void blankOcrCreditScoreLabelWithoutResolvedScoreForcesReview() {
        String text = "Credit Score:\n768\nIssue Date:\n04/19/2026";
        PageText page = new PageText(15, 612, 792, text, List.of(), ExtractionSource.OCR, 0.96);

        var result = policy.evaluate(Map.of(15, page), List.of(), enabled);

        assertTrue(result.locatedEntities().isEmpty());
        assertEquals(1, result.reviewSignals().size());
        assertEquals(EntityType.CREDIT_SCORE, result.reviewSignals().getFirst().entityType());
    }

    @Test
    void blankOcrCreditScoreLabelDoesNotForceReviewWhenScoreAlreadyResolvedOnPage() {
        String text = "Credit Score:\n768";
        int start = text.indexOf("768");
        DetectedEntity existing = new DetectedEntity(
                "CREDIT_SCORE", 15, start, start + 3, 0.90,
                "***", "existing-detector", "test");
        PageText page = new PageText(15, 612, 792, text, List.of(), ExtractionSource.OCR, 0.96);

        var result = policy.evaluate(Map.of(15, page), List.of(existing), enabled);

        assertTrue(result.locatedEntities().isEmpty());
        assertTrue(result.reviewSignals().isEmpty());
    }

    @Test
    void spatialFallbackForcesReviewForAmbiguousValueInsteadOfSilentExport() {
        String text = "Credit Score:\n76B\nIssue Date:\n04/19/2026";
        PageText page = spatialPage(text, "76B");

        var result = policy.evaluate(Map.of(15, page), List.of(), enabled);

        assertTrue(result.locatedEntities().isEmpty());
        assertEquals(1, result.reviewSignals().size());
        assertEquals(EntityType.CREDIT_SCORE, result.reviewSignals().getFirst().entityType());
        assertEquals(1, result.spatialScoreCandidateCount());
    }

    @Test
    void spatialFallbackToleratesSmallOcrVerticalBoxShift() {
        String text = "Credit Score:\n768";
        List<TextSpan> spans = new ArrayList<>();
        addSpan(spans, text, "Credit", 0.19, 0.45, 0.05, 0.015);
        addSpan(spans, text, "Score:", 0.25, 0.45, 0.06, 0.015);
        addSpan(spans, text, "768", 0.42, 0.476, 0.04, 0.012);
        PageText page = new PageText(15, 612, 792, text, spans, ExtractionSource.OCR, 0.96);

        var result = policy.evaluate(Map.of(15, page), List.of(), enabled);

        assertEquals(1, result.locatedEntities().size());
        assertEquals("CREDIT_SCORE", result.locatedEntities().getFirst().entityType());
        assertTrue(result.reviewSignals().isEmpty());
    }

    @Test
    void existingLowConfidenceScoreDoesNotSuppressStrongOcrGuardEvidence() {
        String text = "Credit Score: 768";
        int start = text.indexOf("768");
        DetectedEntity existing = new DetectedEntity(
                "CREDIT_SCORE", 15, start, start + 3, 0.70,
                "***", "semantic", "test");

        var result = policy.evaluate(Map.of(15, ocrPage(15, text)), List.of(existing), enabled);

        assertEquals(1, result.locatedEntities().size());
        assertEquals(0.94, result.locatedEntities().getFirst().confidence(), 0.0001);
        assertTrue(result.reviewSignals().isEmpty());
    }

    @Test
    void genericPrivacyLanguageDoesNotCreateReviewSignal() {
        String text = "The information we collect can include Social Security number and income, credit scores and payment history.";
        var result = policy.evaluate(Map.of(8, ocrPage(8, text)), List.of(), enabled);

        assertTrue(result.locatedEntities().isEmpty());
        assertTrue(result.reviewSignals().isEmpty());
    }

    @Test
    void maskedCardFieldDoesNotCreateReviewNoise() {
        String text = "Credit Card Number: XXXXXXXXXXXX1111";
        var result = policy.evaluate(Map.of(4, ocrPage(4, text)), List.of(), enabled);

        assertTrue(result.locatedEntities().isEmpty());
        assertTrue(result.reviewSignals().isEmpty());
    }

    @Test
    void creditScoreCategoryMentionWithOrdinaryWordToRightDoesNotForceReview() {
        String text = "Credit Score and payment history are categories of information only.";
        List<TextSpan> spans = new ArrayList<>();
        addSpan(spans, text, "Credit", 0.10, 0.20, 0.05, 0.010);
        addSpan(spans, text, "Score", 0.16, 0.20, 0.05, 0.010);
        addSpan(spans, text, "and", 0.23, 0.20, 0.03, 0.010);
        PageText page = new PageText(6, 612, 792, text, spans, ExtractionSource.OCR, 0.96);

        var result = policy.evaluate(Map.of(6, page), List.of(), enabled);

        assertTrue(result.locatedEntities().isEmpty());
        assertTrue(result.reviewSignals().isEmpty());
        assertEquals(1, result.spatialScoreCandidateCount());
    }

    @Test
    void preMaskedOcrFieldArtifactsDoNotCreateImpossibleReviewItems() {
        String text = String.join("\n",
                "Applicant Name: Casey Rowan",
                "Social Security Number: |",
                "Date of Birth: |",
                "Credit Card Number: Pe",
                "Credit Score: | |",
                "Issue Date: 04/19/2026");
        List<TextSpan> spans = new ArrayList<>();

        // Normal text establishes the page's ordinary OCR glyph height (~0.009).
        addSpan(spans, text, "Applicant", 0.10, 0.10, 0.06, 0.009);
        addSpan(spans, text, "Name:", 0.17, 0.10, 0.04, 0.009);
        addSpan(spans, text, "Casey", 0.30, 0.10, 0.04, 0.009);
        addSpan(spans, text, "Rowan", 0.35, 0.10, 0.04, 0.009);
        addSpan(spans, text, "Social", 0.10, 0.20, 0.05, 0.009);
        addSpan(spans, text, "Security", 0.16, 0.20, 0.06, 0.009);
        addSpan(spans, text, "Number:", 0.23, 0.20, 0.07, 0.009);
        addSpan(spans, text, "Date", 0.10, 0.30, 0.04, 0.009);
        addSpan(spans, text, "Birth:", 0.19, 0.30, 0.05, 0.009);
        addSpan(spans, text, "Card", 0.16, 0.40, 0.04, 0.009);
        addSpan(spans, text, "Issue", 0.10, 0.60, 0.04, 0.009);

        addSpan(spans, text, "|", 0.42, 0.20, 0.011, 0.017, 0.48);
        addSpanAtOccurrence(spans, text, "|", 1, 0.42, 0.30, 0.014, 0.018, 0.52);
        addSpan(spans, text, "Pe", 0.42, 0.40, 0.16, 0.012, 0.13);
        addSpanAtOccurrence(spans, text, "|", 2, 0.42, 0.50, 0.014, 0.024, 0.60);
        addSpanAtOccurrence(spans, text, "|", 3, 0.46, 0.50, 0.018, 0.022, 0.60);

        PageText page = new PageText(15, 612, 792, text, spans, ExtractionSource.OCR, 0.85);
        var result = policy.evaluate(Map.of(15, page), List.of(), enabled);

        assertTrue(result.locatedEntities().isEmpty());
        assertTrue(result.reviewSignals().isEmpty());
    }

    @Test
    void blankStrongLabelsBesideVerifiedSourceBlackoutsDoNotCreateReviewItems() {
        String text = String.join("\n",
                "Social Security Number:",
                "Date of Birth:",
                "Credit Card Number:",
                "Credit Score:",
                "Issue Date: 04/19/2026");
        List<TextSpan> spans = new ArrayList<>();
        addWholeLabelSpan(spans, text, "Social Security Number", 0.19, 0.31, 0.17, 0.012);
        addWholeLabelSpan(spans, text, "Date of Birth", 0.19, 0.36, 0.14, 0.012);
        addWholeLabelSpan(spans, text, "Credit Card Number", 0.19, 0.40, 0.15, 0.012);
        addWholeLabelSpan(spans, text, "Credit Score", 0.19, 0.45, 0.12, 0.012);

        List<VisualRegion> visualRegions = List.of(
                darkRectangle(0.416, 0.312, 0.095, 0.012),
                darkRectangle(0.416, 0.358, 0.086, 0.013),
                darkRectangle(0.416, 0.403, 0.164, 0.012),
                darkRectangle(0.409, 0.442, 0.051, 0.021));

        PageText page = new PageText(15, 612, 792, text, spans, visualRegions, ExtractionSource.OCR, 0.90);
        var result = policy.evaluate(Map.of(15, page), List.of(), enabled);

        assertTrue(result.locatedEntities().isEmpty());
        assertTrue(result.reviewSignals().isEmpty());
    }

    @Test
    void blankStrongLabelWithoutAlignedVisualBlackoutStillFailsSafe() {
        String text = "Date of Birth:";
        List<TextSpan> spans = new ArrayList<>();
        addWholeLabelSpan(spans, text, "Date of Birth", 0.19, 0.36, 0.14, 0.012);
        List<VisualRegion> visualRegions = List.of(
                darkRectangle(0.70, 0.70, 0.10, 0.012));

        PageText page = new PageText(15, 612, 792, text, spans, visualRegions, ExtractionSource.OCR, 0.90);
        var result = policy.evaluate(Map.of(15, page), List.of(), enabled);

        assertTrue(result.locatedEntities().isEmpty());
        assertEquals(1, result.reviewSignals().size());
        assertEquals(EntityType.DATE_OF_BIRTH, result.reviewSignals().getFirst().entityType());
    }

    @Test
    void visibleValueIsNotHiddenByVisualBlackoutEvidenceElsewhereOnSameRow() {
        String text = "Social Security Number: 123-45-6789";
        List<TextSpan> spans = new ArrayList<>();
        addWholeLabelSpan(spans, text, "Social Security Number", 0.19, 0.31, 0.17, 0.012);
        addSpan(spans, text, "123-45-6789", 0.42, 0.31, 0.09, 0.012, 0.95);
        List<VisualRegion> visualRegions = List.of(
                darkRectangle(0.55, 0.31, 0.08, 0.012));

        PageText page = new PageText(15, 612, 792, text, spans, visualRegions, ExtractionSource.OCR, 0.95);
        var result = policy.evaluate(Map.of(15, page), List.of(), enabled);

        assertTrue(result.locatedEntities().isEmpty());
        assertEquals(1, result.reviewSignals().size());
        assertEquals(EntityType.SSN, result.reviewSignals().getFirst().entityType());
    }

    @Test
    void normalUnreadablePaymentCardValueStillFailsSafeToManualReview() {
        String text = "Credit Card Number: ABC";
        List<TextSpan> spans = new ArrayList<>();
        addSpan(spans, text, "ABC", 0.42, 0.40, 0.04, 0.010, 0.90);
        PageText page = new PageText(15, 612, 792, text, spans, ExtractionSource.OCR, 0.90);

        var result = policy.evaluate(Map.of(15, page), List.of(), enabled);

        assertTrue(result.locatedEntities().isEmpty());
        assertEquals(1, result.reviewSignals().size());
        assertEquals(EntityType.CREDIT_CARD_NUMBER, result.reviewSignals().getFirst().entityType());
    }

    @Test
    void ordinaryVerticalBarWithoutMaskGeometryStillFailsSafe() {
        String text = "Date of Birth: |";
        List<TextSpan> spans = new ArrayList<>();
        addSpan(spans, text, "|", 0.42, 0.40, 0.006, 0.010, 0.90);
        PageText page = new PageText(15, 612, 792, text, spans, ExtractionSource.OCR, 0.90);

        var result = policy.evaluate(Map.of(15, page), List.of(), enabled);

        assertTrue(result.locatedEntities().isEmpty());
        assertEquals(1, result.reviewSignals().size());
        assertEquals(EntityType.DATE_OF_BIRTH, result.reviewSignals().getFirst().entityType());
    }

    @Test
    void nativeTextPageIsNotAffectedByOcrSafetyPolicy() {
        String text = "Credit Score: 768";
        PageText nativePage = new PageText(15, 612, 792, text, List.of(), ExtractionSource.NATIVE_PDF_TEXT, 1.0);
        var result = policy.evaluate(Map.of(15, nativePage), List.of(), enabled);

        assertTrue(result.locatedEntities().isEmpty());
        assertTrue(result.reviewSignals().isEmpty());
    }

    private DetectedEntity entity(String text, String type, String value) {
        int start = text.indexOf(value);
        return new DetectedEntity(type, 15, start, start + value.length(), 0.90,
                "***", "existing-detector", "test");
    }

    private PageText ocrPage(int pageNumber, String text) {
        return new PageText(pageNumber, 612, 792, text, List.of(), ExtractionSource.OCR, 0.96);
    }

    /**
     * Simulates Tesseract logical block ordering where the label and value are separated in text,
     * while their OCR boxes are still on the same visual row.
     */
    private PageText spatialPage(String text, String scoreValue) {
        List<TextSpan> spans = new ArrayList<>();
        addSpan(spans, text, "Credit", 0.19, 0.45, 0.05, 0.02);
        addSpan(spans, text, "Score:", 0.25, 0.45, 0.06, 0.02);
        addSpan(spans, text, scoreValue, 0.42, 0.45, 0.04, 0.02);
        addSpan(spans, text, "Issue", 0.19, 0.50, 0.04, 0.02);
        addSpan(spans, text, "Date:", 0.24, 0.50, 0.05, 0.02);
        addSpan(spans, text, "04/19/2026", 0.42, 0.50, 0.08, 0.02);
        return new PageText(15, 612, 792, text, spans, ExtractionSource.OCR, 0.96);
    }

    private VisualRegion darkRectangle(double left, double top, double width, double height) {
        return new VisualRegion(
                VisualRegionType.SOLID_DARK_RECTANGLE, left, top, width, height, 1.0);
    }

    private void addWholeLabelSpan(List<TextSpan> spans, String text, String label,
                                   double left, double top, double width, double height) {
        int start = text.indexOf(label);
        spans.add(new TextSpan(start, start + label.length(), left, top, width, height, 0.96));
    }

    private void addSpan(List<TextSpan> spans, String text, String token,
                         double left, double top, double width, double height) {
        addSpan(spans, text, token, left, top, width, height, 0.96);
    }

    private void addSpan(List<TextSpan> spans, String text, String token,
                         double left, double top, double width, double height, double confidence) {
        int start = text.indexOf(token);
        spans.add(new TextSpan(start, start + token.length(), left, top, width, height, confidence));
    }

    private void addSpanAtOccurrence(List<TextSpan> spans, String text, String token, int occurrence,
                                     double left, double top, double width, double height, double confidence) {
        int start = -1;
        int from = 0;
        for (int i = 0; i <= occurrence; i++) {
            start = text.indexOf(token, from);
            if (start < 0) {
                throw new IllegalArgumentException("Token occurrence not found: " + token + " #" + occurrence);
            }
            from = start + token.length();
        }
        spans.add(new TextSpan(start, start + token.length(), left, top, width, height, confidence));
    }
}
