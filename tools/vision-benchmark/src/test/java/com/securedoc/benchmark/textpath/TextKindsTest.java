package com.securedoc.benchmark.textpath;

import com.securedoc.benchmark.truth.Truth;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0043: the product's new kind names are scored against the answer files' types. */
class TextKindsTest {

    @Test
    void newProductKindsMapOntoAnswerFileTypes() {
        assertEquals("ROUTING_NUMBER", TextPathRunner.answerType("BANK_ROUTING_NUMBER"));
        assertEquals("BANK_ACCOUNT", TextPathRunner.answerType("BANK_ACCOUNT_NUMBER"));
        assertEquals("GOVERNMENT_ID", TextPathRunner.answerType("DRIVERS_LICENSE_NUMBER"));
        assertEquals("GOVERNMENT_ID", TextPathRunner.answerType("PASSPORT_NUMBER"));
        assertEquals("SSN", TextPathRunner.answerType("SSN"), "V1 names unchanged");
        assertEquals("ITIN", TextPathRunner.answerType("ITIN"), "no answer-file type: counted apart");
        for (String t : Truth.R1_PATTERN_TYPES) assertTrue(Truth.ITEM_TYPES.contains(t));
        assertTrue(Truth.TEXT_PATH_R1_TYPES.containsAll(Truth.TEXT_PATH_TYPES));
    }
}
