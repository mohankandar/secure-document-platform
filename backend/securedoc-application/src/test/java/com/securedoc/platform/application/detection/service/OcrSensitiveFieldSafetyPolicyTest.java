package com.securedoc.platform.application.detection.service;

import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.DetectedEntity;
import com.securedoc.platform.application.port.out.TextExtractionPort.ExtractionSource;
import com.securedoc.platform.application.port.out.TextExtractionPort.PageText;
import com.securedoc.platform.application.port.out.TextExtractionPort.TextSpan;
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

    private void addSpan(List<TextSpan> spans, String text, String token,
                         double left, double top, double width, double height) {
        int start = text.indexOf(token);
        spans.add(new TextSpan(start, start + token.length(), left, top, width, height, 0.96));
    }
}
