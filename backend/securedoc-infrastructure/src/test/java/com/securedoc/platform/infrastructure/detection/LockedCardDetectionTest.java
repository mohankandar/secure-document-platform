package com.securedoc.platform.infrastructure.detection;

import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.DetectedEntity;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.PageInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0099: the lines of the owner's test of 2026-11-25 that were delivered visible (R1 to R3), and near-misses that
 * must stay unflagged. Fake values only.
 */
class LockedCardDetectionTest {

    private final LocalRuleBasedDetectionAdapter rules = new LocalRuleBasedDetectionAdapter();

    @Test
    void r1PaidByCardWithItsExpiryAndCode() {
        String text = "Balance: $38,214.55\nApplication fee paid by card: 4111 1111 1111 1111  exp 08/29  CVV 123\n"
                + "Credit score (Experian): 742";
        assertEquals("CREDIT_CARD_NUMBER=4111 1111 1111 1111@0.95 CREDIT_SCORE=742@0.85 CVV=123@0.95", found(text));
    }

    @Test
    void r2TheCardAsOcrReadsIt() {
        assertEquals("CREDIT_CARD_NUMBER=41111111 1111 1111@0.95",
                found("09/11 Card 41111111 1111 1111 purchase -86.20"));
        // l, I, | for 1 and O for 0, up to three of them.
        assertEquals("CREDIT_CARD_NUMBER=4lll 1111 1111 1111@0.95", found("Card 4lll 1111 1111 1111 purchase"));
        assertEquals("CREDIT_CARD_NUMBER=4000 O566 5566 5556@0.95", found("Card 4000 O566 5566 5556"));
        assertEquals("", found("Card 4III IIII 1111 1111"), "too many letters: not read as a number");
        assertEquals("", found("Card 4lll 1111 1111 1112"), "the check digit still decides");
    }

    @Test
    void r3TheHiddenLinesOfTheOwnersFile() {
        assertEquals("SSN=512-34-8876@0.95", found("Hidden: borrower SSN 512-34-8876 account 4820193746"));
        assertEquals("DATE_OF_BIRTH=03/14/1979@0.9 CREDIT_CARD_NUMBER=4111111111111111@0.95",
                found("tiny text DOB 03/14/1979 card 4111111111111111"));
    }

    @Test
    void aCardWithoutALabelIsFoundForAPersonToDecide() {
        assertEquals("CREDIT_CARD_NUMBER=4111 1111 1111 1111@0.75", found("Posted 4111 1111 1111 1111 on 10/02"));
        assertEquals("CREDIT_CARD_NUMBER=3782 822463 10005@0.75", found("Posted 3782 822463 10005"));
    }

    @Test
    void numbersThatAreNoCardAreLeftAlone() {
        for (String text : List.of("Contract Number: 4111111111111111", "Ref 4111111111111111",
                "Contract number 4111 1111 1111 1111", "Account 1234 5678 9012 3456",
                "Order 12-3456 shipped 3 boxes; invoice 512 34 88", "Tracking 9400 1000 0000 0000 0000 00",
                "Card on file ending 1111")) {
            assertFalse(found(text).contains("CREDIT_CARD_NUMBER"), text);
        }
    }

    @Test
    void anSsnLabelAfterItsValueStillLosesToABusinessLabelBeforeIt() {
        assertEquals("", found("Form Number 412-55-7531 is not a Social Security Number"));
        assertEquals("", found("Reference number 412-55-7531 (SSN on file)"));
        assertEquals("SSN=412-55-7531@0.95", found("SSN 412-55-7531 Account 4820193746"));
    }

    @Test
    void issuerPrefixes() {
        assertTrue(LocalRuleBasedDetectionAdapter.hasCardIssuerPrefix("4111111111111111"));
        assertTrue(LocalRuleBasedDetectionAdapter.hasCardIssuerPrefix("5500000000000004"));
        assertTrue(LocalRuleBasedDetectionAdapter.hasCardIssuerPrefix("2221000000000009"));
        assertTrue(LocalRuleBasedDetectionAdapter.hasCardIssuerPrefix("378282246310005"));
        assertTrue(LocalRuleBasedDetectionAdapter.hasCardIssuerPrefix("6011111111111117"));
        assertFalse(LocalRuleBasedDetectionAdapter.hasCardIssuerPrefix("1234567890123452"));
        assertFalse(LocalRuleBasedDetectionAdapter.hasCardIssuerPrefix("411111111111111"), "Visa of 15 digits");
        assertEquals("4111 1001", LocalRuleBasedDetectionAdapter.ocrDigits("4lI| 1OO|"));
    }

    /** Kinds of the V1, opt-in and locked rules, with their values (fake) and confidence. */
    private String found(String text) {
        StringBuilder all = new StringBuilder();
        for (DetectedEntity e : rules.detect(List.of(new PageInput(1, text))).entities()) {
            if (!List.of("SSN", "DATE_OF_BIRTH", "CREDIT_CARD_NUMBER", "CREDIT_SCORE", "CVV", "PIN")
                    .contains(e.entityType())) continue;
            if (!all.isEmpty()) all.append(' ');
            all.append(e.entityType()).append('=').append(text, e.startOffset(), e.endOffset())
                    .append('@').append(e.confidence());
        }
        return all.toString();
    }
}
