package com.securedoc.platform.application.operations;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0020 B1/B2: every number rounds down; minutes per page 0.5 to 30 in steps of 0.5. */
class TimeSavedRuleTest {

    @Test
    void minutesPerPageAllowedValues() {
        for (double ok : new double[]{0.5, 1, 1.5, 2.5, 29.5, 30}) assertTrue(TimeSavedRule.allowedMinutesPerPage(ok), "" + ok);
        for (double bad : new double[]{0.4, 30.5, 1.25, 0, -0.5, 31, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertFalse(TimeSavedRule.allowedMinutesPerPage(bad), "" + bad);
        }
    }

    @Test
    void minutesSavedIsPagesTimesMinutesRoundedDown() {
        assertEquals(9_690, TimeSavedRule.minutesSaved(9_690, 1.0), "the canvas example");
        assertEquals(181, TimeSavedRule.minutesSaved(121, 1.5), "181.5 rounds down");
        assertEquals(302, TimeSavedRule.minutesSaved(121, 2.5), "302.5 rounds down");
        assertEquals(0, TimeSavedRule.minutesSaved(1, 0.5), "0.5 rounds down to 0");
        assertEquals(0, TimeSavedRule.minutesSaved(0, 30));
        assertEquals(3_000_000_000L, TimeSavedRule.minutesSaved(100_000_000L, 30), "no overflow for large counts");
    }

    @Test
    void percentIsRoundedDownAndNullWithNoCases() {
        assertEquals(67, TimeSavedRule.percentRoundedDown(348, 512), "67.97% is 67, never 68");
        assertEquals(25, TimeSavedRule.percentRoundedDown(1, 4));
        assertEquals(100, TimeSavedRule.percentRoundedDown(4, 4));
        assertEquals(0, TimeSavedRule.percentRoundedDown(0, 4));
        assertNull(TimeSavedRule.percentRoundedDown(0, 0));
    }

    @Test
    void timeWordsHoursRoundedDownOrMinutesUnderAnHour() {
        assertEquals("≈ 161 h", TimeSavedRule.timeWords(9_690), "the canvas example: 9,690 minutes");
        assertEquals("≈ 1 h", TimeSavedRule.timeWords(119));
        assertEquals("≈ 1 h", TimeSavedRule.timeWords(60));
        assertEquals("≈ 59 min", TimeSavedRule.timeWords(59));
        assertEquals("0 min", TimeSavedRule.timeWords(0));
        assertEquals("≈ 1,666 h", TimeSavedRule.timeWords(100_000));
    }

    @Test
    void minutesPerPageWords() {
        assertEquals("1", TimeSavedRule.minutesPerPageWords(1.0));
        assertEquals("2.5", TimeSavedRule.minutesPerPageWords(2.5));
        assertEquals("1.0", TimeSavedRule.minutesPerPageKey(1.0));
        assertEquals("30.0", TimeSavedRule.minutesPerPageKey(30));
    }
}
