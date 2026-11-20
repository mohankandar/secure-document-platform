package com.securedoc.platform.application.compliance;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Task 0017 B2: policy values read from the 0016 audit details, and the change lines in plain words. */
class PolicyHistoryTest {

    private static final String BALANCED_TO_LIGHT = "Sensitive-data policy changed: enabledCount=3, threshold=0.70, "
            + "reviewMode=UNCERTAIN_ONLY, maskSsnLastFour=true, enabledTypes=CREDIT_SCORE,DATE_OF_BIRTH,SSN, preset=LIGHT, "
            + "previous=[enabledCount=3, threshold=0.80, reviewMode=UNCERTAIN_ONLY, maskSsnLastFour=false, "
            + "enabledTypes=CREDIT_CARD_NUMBER,DATE_OF_BIRTH,SSN, preset=BALANCED]";

    @Test
    void changeLinesNameEveryChangedValueInCanvasWords() {
        List<String> lines = PolicyHistory.changeLines(PolicyHistory.parseSensitive(BALANCED_TO_LIGHT));
        assertEquals(List.of(
                "Preset: Balanced → Light",
                "Redacted without review at: 80% → 70%",
                "Credit score turned on",
                "Credit card number turned off",
                "Labeled last-four SSN treated as sensitive: No → Yes"), lines);
    }

    @Test
    void reviewModeChange() {
        String detail = "Sensitive-data policy changed: enabledCount=1, threshold=0.85, reviewMode=REVIEW_ALL, "
                + "maskSsnLastFour=true, enabledTypes=SSN, preset=STRICT, previous=[enabledCount=1, threshold=0.85, "
                + "reviewMode=UNCERTAIN_ONLY, maskSsnLastFour=true, enabledTypes=SSN, preset=CUSTOM]";
        assertEquals(List.of("Preset: Custom → Strict", "Sent to a person: Only uncertain findings → Every case"),
                PolicyHistory.changeLines(PolicyHistory.parseSensitive(detail)));
    }

    @Test
    void firstSaveOldFormatAndNoChange() {
        String first = "Sensitive-data policy changed: enabledCount=1, threshold=0.80, reviewMode=UNCERTAIN_ONLY, "
                + "maskSsnLastFour=true, enabledTypes=SSN, preset=BALANCED, previous=none";
        assertEquals(List.of("Policy saved for the first time"),
                PolicyHistory.changeLines(PolicyHistory.parseSensitive(first)));

        String old = "Sensitive-data policy changed: enabledCount=4, threshold=0.80, reviewMode=REVIEW_ALL, maskSsnLastFour=true";
        PolicyHistory.SensitiveChange parsed = PolicyHistory.parseSensitive(old);
        assertNull(parsed.now().types(), "names were not recorded before 0016");
        assertEquals(4, parsed.now().enabledCount());
        assertEquals(List.of("Changed (this record does not hold the earlier values)"), PolicyHistory.changeLines(parsed));

        String same = "Sensitive-data policy changed: enabledCount=1, threshold=0.80, reviewMode=UNCERTAIN_ONLY, "
                + "maskSsnLastFour=true, enabledTypes=SSN, preset=BALANCED, previous=[enabledCount=1, threshold=0.80, "
                + "reviewMode=UNCERTAIN_ONLY, maskSsnLastFour=true, enabledTypes=SSN, preset=BALANCED]";
        assertEquals(List.of("Saved with no change"), PolicyHistory.changeLines(PolicyHistory.parseSensitive(same)));
    }

    @Test
    void newValuesAreRead() {
        PolicyHistory.SensitivePolicy now = PolicyHistory.parseSensitive(BALANCED_TO_LIGHT).now();
        assertEquals(Set.of("CREDIT_SCORE", "DATE_OF_BIRTH", "SSN"), now.types());
        assertEquals(0.70, now.threshold(), 1e-9);
        assertEquals("LIGHT", now.preset());
        assertNull(PolicyHistory.parseSensitive("Something else: threshold=0.5"));
    }

    @Test
    void lifecycleValuesAndWords() {
        PolicyHistory.LifecyclePolicy p = PolicyHistory.parseLifecycle("Lifecycle policy changed: originalRetentionHours=72, "
                + "redactedCacheHours=24, neverExportedHoldHours=336, finalApprovalRequired=true");
        assertEquals(new PolicyHistory.LifecyclePolicy(72, 24, 336, true), p);
        assertNull(PolicyHistory.parseLifecycle("Lifecycle policy changed: originalRetentionHours=72"));
        assertEquals("14 days", PolicyHistory.hoursWords(336));
        assertEquals("24 hours", PolicyHistory.hoursWords(24));
        assertEquals("3 days", PolicyHistory.hoursWords(72));
        assertEquals("30 hours", PolicyHistory.hoursWords(30));
    }

    @Test
    void typeNamesComeFromTheVocabulary() {
        assertEquals("Social Security Number", SensitiveTypeNames.name("SSN"));
        assertEquals("Social Security Number", SensitiveTypeNames.name("US_SSN"));
        assertEquals("Date of birth", SensitiveTypeNames.name("DOB"));
        assertEquals("Phone number", SensitiveTypeNames.name("PHONE_NUMBER"));
        assertEquals("Government ID", SensitiveTypeNames.name("GOVERNMENT_ID"));
    }

    @Test
    void auditSentencesMatchTheAuditTrailScreen() {
        assertEquals("Redacted a Social Security Number", AuditSentences.whatHappened("FINDING_CONFIRMED", "Finding confirmed: type=SSN"));
        assertEquals("Kept a credit score visible", AuditSentences.whatHappened("FINDING_DISMISSED", "type=CREDIT_SCORE"));
        assertEquals("Approved delivery", AuditSentences.whatHappened("PACKAGE_FINAL_APPROVED", null));
        assertEquals("Compliance report created", AuditSentences.whatHappened("COMPLIANCE_REPORT_CREATED", null));
        assertEquals("LexaVault (automatic)", AuditSentences.person(AuditSentences.SYSTEM_ACTOR_ID, "x"));
        assertEquals("Scanner: front desk", AuditSentences.person("11111111-1111-1111-1111-111111111111", "Scanner: front desk"));
        assertEquals("Person 22222222", AuditSentences.person("11111111-1111-1111-1111-122222222222", null));
        assertEquals("Refused", AuditSentences.resultWord("DENIED"));
    }
}
