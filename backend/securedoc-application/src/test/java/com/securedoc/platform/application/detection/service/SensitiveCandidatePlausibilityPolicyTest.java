package com.securedoc.platform.application.detection.service;

import com.securedoc.platform.application.port.out.SensitiveDataValidationPort.ValidationOutcome;
import com.securedoc.platform.domain.finding.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveCandidatePlausibilityPolicyTest {

    private final SensitiveCandidatePlausibilityPolicy policy = new SensitiveCandidatePlausibilityPolicy();

    @Test
    void rejectsPersonNameMisclassifiedAsDateOfBirthEvenAtAnyConfidence() {
        var result = evaluate(EntityType.DATE_OF_BIRTH, "Barbara L Axe",
                "Owner's Signature: Barbara L Axe", ValidationOutcome.NOT_APPLICABLE);

        assertFalse(result.plausible());
        assertEquals("DOB_VALUE_NOT_DATE", result.reasonCode());
    }

    @Test
    void rejectsFinancialAndDocumentDatesAsDateOfBirth() {
        assertFalse(evaluate(EntityType.DATE_OF_BIRTH, "05-22-2026",
                "First Payment Date 05-22-2026", ValidationOutcome.NOT_APPLICABLE).plausible());
        assertFalse(evaluate(EntityType.DATE_OF_BIRTH, "04-19-2026",
                "Contract # 00015-2600124 Date: 04-19-2026", ValidationOutcome.NOT_APPLICABLE).plausible());
    }

    @Test
    void nearbyBirthLabelDoesNotLegitimizeAnUnrelatedFinancialDate() {
        var result = evaluate(EntityType.DATE_OF_BIRTH, "05-22-2026",
                "Date of Birth: 01/05/1980 ........................................................... "
                        + "First Payment Date: 05-22-2026",
                ValidationOutcome.NOT_APPLICABLE);

        assertFalse(result.plausible());
        assertEquals("DOB_CONTEXT_NOT_BIRTH", result.reasonCode());
    }

    @Test
    void acceptsPlausibleBirthDateWithBirthContext() {
        var result = evaluate(EntityType.DATE_OF_BIRTH, "01/05/1980",
                "Customer Date of Birth: 01/05/1980", ValidationOutcome.SUPPORTS);

        assertTrue(result.plausible());
    }

    @Test
    void rejectsSsnShapedBusinessReferenceWithoutSsnContext() {
        var result = evaluate(EntityType.SSN, "123-45-6789",
                "Reference Number: 123-45-6789", ValidationOutcome.SUPPORTS);

        assertFalse(result.plausible());
        assertEquals("SSN_BUSINESS_IDENTIFIER_CONTEXT", result.reasonCode());
    }

    @Test
    void genericSsnDiscussionDoesNotLegitimizeNearbyBusinessReference() {
        var result = evaluate(EntityType.SSN, "123-45-6789",
                "Reference Number: 123-45-6789. This notice describes information such as "
                        + "Social Security number and income.",
                ValidationOutcome.SUPPORTS);

        assertFalse(result.plausible());
        assertEquals("SSN_BUSINESS_IDENTIFIER_CONTEXT", result.reasonCode());
    }

    @Test
    void acceptsActualSsnWithExplicitContext() {
        assertTrue(evaluate(EntityType.SSN, "123-45-6789",
                "Social Security Number: 123-45-6789", ValidationOutcome.SUPPORTS).plausible());
    }

    @Test
    void maskedCardLastFourIsNotAStandaloneV1Finding() {
        var result = evaluate(EntityType.CREDIT_CARD_NUMBER, "XXXX6304",
                "Credit Card #: XXXX6304", ValidationOutcome.CONTRADICTS);

        assertFalse(result.plausible());
        assertEquals("CARD_NOT_FULL_PAN", result.reasonCode());
    }

    @Test
    void explicitCardContextKeepsPlausibleFullPanEvenWhenLuhnContradicts() {
        var result = evaluate(EntityType.CREDIT_CARD_NUMBER, "4111 1111 1111 1112",
                "Credit Card Number: 4111 1111 1111 1112", ValidationOutcome.CONTRADICTS);

        assertTrue(result.plausible());
    }

    @Test
    void rejectsBusinessIdentifierThatOnlyLooksLikePan() {
        var result = evaluate(EntityType.CREDIT_CARD_NUMBER, "4111111111111111",
                "Contract Number: 4111111111111111", ValidationOutcome.SUPPORTS);

        assertFalse(result.plausible());
    }

    @Test
    void acceptsActualCreditScoreAndRejectsExplanatoryRange() {
        assertTrue(evaluate(EntityType.CREDIT_SCORE, "780",
                "Your credit score 780 Source: Equifax", ValidationOutcome.SUPPORTS).plausible());

        assertFalse(evaluate(EntityType.CREDIT_SCORE, "300",
                "The range of scores: Scores range from a low of 300 to a high of 850.",
                ValidationOutcome.SUPPORTS).plausible());
        assertFalse(evaluate(EntityType.CREDIT_SCORE, "850",
                "The range of scores: Scores range from a low of 300 to a high of 850.",
                ValidationOutcome.SUPPORTS).plausible());
    }

    @Test
    void keepsActualScoreWhenTheSamePageAlsoExplainsTheScoreRange() {
        String pageContext = "Your credit score 780 Source: Equifax Date: 12-20-2025. "
                + "What you should know about credit scores: Your credit score is a number that reflects "
                + "the information in your credit report. The range of scores: Scores range from a low "
                + "of 300 to a high of 850.";

        assertTrue(evaluate(EntityType.CREDIT_SCORE, "780", pageContext,
                ValidationOutcome.SUPPORTS).plausible());
        assertFalse(evaluate(EntityType.CREDIT_SCORE, "300", pageContext,
                ValidationOutcome.SUPPORTS).plausible());
        assertFalse(evaluate(EntityType.CREDIT_SCORE, "850", pageContext,
                ValidationOutcome.SUPPORTS).plausible());
    }

    @Test
    void rejectsBareThreeDigitNumberAsCreditScore() {
        assertFalse(evaluate(EntityType.CREDIT_SCORE, "720",
                "Invoice quantity: 720 units", ValidationOutcome.NOT_APPLICABLE).plausible());
    }

    private SensitiveCandidatePlausibilityPolicy.Evaluation evaluate(EntityType type,
                                                                     String value,
                                                                     String context,
                                                                     ValidationOutcome validation) {
        return policy.evaluate(type, value, context, true, validation);
    }
}
