package com.securedoc.platform.api.policy;

import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.PageInput;
import com.securedoc.platform.infrastructure.detection.LocalRuleBasedDetectionAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicContextPrecisionTest {

    private final LocalRuleBasedDetectionAdapter detector = new LocalRuleBasedDetectionAdapter();

    @Test
    void ssnShapedBusinessReferenceIsNotDeterministicallyDiscovered() {
        var output = detector.detect(List.of(new PageInput(1,
                "Servicing Reference Number: 123-45-6789")));

        assertThat(output.entities()).noneMatch(entity -> entity.entityType().equals("SSN"));
    }

    @Test
    void explicitlyLabeledSsnStillProducesSupportingDetection() {
        var output = detector.detect(List.of(new PageInput(1,
                "Social Security Number: 123-45-6789")));

        assertThat(output.entities()).anyMatch(entity -> entity.entityType().equals("SSN"));
    }

    @Test
    void clearlyLabeledSyntheticSsnIsProtectedEvenWhenNumberIsNonAssignable() {
        var output = detector.detect(List.of(new PageInput(1,
                "Applicant: Jordan Avery  Social Security Number: 987-65-4321")));

        assertThat(output.entities()).anyMatch(entity -> entity.entityType().equals("SSN"));
    }

    @Test
    void formReferenceIsNotPromotedToSsnByNearbyNegatedSsnWording() {
        var output = detector.detect(List.of(new PageInput(1,
                "Form Reference: 123-45-6789. This is a synthetic document reference in this notice, "
                        + "not a Social Security Number.")));

        assertThat(output.entities()).noneMatch(entity -> entity.entityType().equals("SSN"));
    }

    @Test
    void luhnValidContractNumberIsNotDeterministicallyDiscoveredAsCard() {
        var output = detector.detect(List.of(new PageInput(1,
                "Contract Number: 4111111111111111")));

        assertThat(output.entities()).noneMatch(entity -> entity.entityType().equals("CREDIT_CARD_NUMBER"));
    }

    @Test
    void actualCardNumberWithPaymentContextStillProducesSupportingDetection() {
        var output = detector.detect(List.of(new PageInput(1,
                "Credit Card Number: 4111 1111 1111 1111")));

        assertThat(output.entities()).anyMatch(entity -> entity.entityType().equals("CREDIT_CARD_NUMBER"));
    }

    @Test
    void explanatoryCreditScoreRangeIsNotAConsumerScoreFinding() {
        var output = detector.detect(List.of(new PageInput(1,
                "Credit score range 300 to 850. Your credit score can change over time.")));

        assertThat(output.entities()).noneMatch(entity -> entity.entityType().equals("CREDIT_SCORE"));
    }

    @Test
    void actualConsumerScoreRemainsDetectedWhenSamePageAlsoExplainsRange() {
        String page = "Your credit score 780 Source: Equifax Date: 12-20-2025. "
                + "What you should know about credit scores: Your credit score is a number that reflects "
                + "the information in your credit report. The range of scores: Scores range from a low "
                + "of 300 to a high of 850.";

        var output = detector.detect(List.of(new PageInput(1, page)));

        assertThat(output.entities()).anyMatch(entity -> entity.entityType().equals("CREDIT_SCORE")
                && entity.startOffset() == page.indexOf("780"));
    }

    @Test
    void actualConsumerCreditScoreIsStillDetected() {
        var output = detector.detect(List.of(new PageInput(1,
                "Your credit score 780 Source: Equifax")));

        assertThat(output.entities()).anyMatch(entity -> entity.entityType().equals("CREDIT_SCORE"));
    }

    @Test
    void creditScoreTableHeaderAppliesToMultipleApplicantRows() {
        String page = "Your Credit Score and Understanding Your Credit Score\n"
                + "Applicant | Your credit score | Source | Date\n"
                + "Jordan Avery | 742 | Synthetic Credit Bureau | 04-19-2026\n"
                + "Morgan Avery | 811 | Synthetic Credit Bureau | 04-19-2026\n"
                + "What you should know about credit scores: a credit score is a number.\n"
                + "The range of scores: Scores range from a low of 300 to a high of 850.";

        var output = detector.detect(List.of(new PageInput(1, page)));

        assertThat(output.entities())
                .filteredOn(entity -> entity.entityType().equals("CREDIT_SCORE"))
                .extracting(entity -> page.substring(entity.startOffset(), entity.endOffset()))
                .containsExactlyInAnyOrder("742", "811");
    }
}
