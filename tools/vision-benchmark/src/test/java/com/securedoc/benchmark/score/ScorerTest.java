package com.securedoc.benchmark.score;

import com.securedoc.benchmark.truth.Truth;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScorerTest {

    private static final Set<String> ALL = Set.copyOf(Truth.ITEM_TYPES);

    private static Truth truth(Truth.Item... items) {
        return new Truth(1, List.of(new Truth.PageTruth(1, "TYPED", "FAST", List.of(items))));
    }

    @Test
    void anOverlappingBoxOfTheSameTypeIsFound() {
        Truth t = truth(new Truth.Item("SSN", new double[]{0.5, 0.1, 0.1, 0.02}, null));
        Scorer.Score s = Scorer.score(t, List.of(new Prediction(1, "SSN", new double[]{0.51, 0.105, 0.1, 0.02}, "x")), p -> true, ALL);
        assertEquals(1, s.total.found);
        assertEquals(0, s.total.misses());
        assertEquals(0, s.total.falseFlags);
    }

    @Test
    void theWrongTypeIsAMissAndAFalseFlag() {
        Truth t = truth(new Truth.Item("SSN", new double[]{0.5, 0.1, 0.1, 0.02}, null));
        Scorer.Score s = Scorer.score(t, List.of(new Prediction(1, "BANK_ACCOUNT", new double[]{0.5, 0.1, 0.1, 0.02}, "x")), p -> true, ALL);
        assertEquals(1, s.total.misses());
        assertEquals(1, s.total.falseFlags);
        assertEquals(1, s.byType.get("SSN").misses());
        assertEquals(1, s.byType.get("BANK_ACCOUNT").falseFlags);
    }

    @Test
    void aBoxElsewhereOnThePageDoesNotCount() {
        Truth t = truth(new Truth.Item("SSN", new double[]{0.5, 0.1, 0.1, 0.02}, null));
        Scorer.Score s = Scorer.score(t, List.of(new Prediction(1, "SSN", new double[]{0.1, 0.6, 0.1, 0.02}, "x")), p -> true, ALL);
        assertEquals(1, s.total.misses());
        assertEquals(1, s.total.falseFlags);
    }

    @Test
    void aBoxOverHalfThePageIsNotAMatch() {
        Truth t = truth(new Truth.Item("SSN", new double[]{0.5, 0.1, 0.1, 0.02}, null));
        Scorer.Score s = Scorer.score(t, List.of(new Prediction(1, "SSN", new double[]{0, 0, 1, 0.5}, "x")), p -> true, ALL);
        assertEquals(1, s.total.misses());
    }

    @Test
    void aFindingWithoutABoxCountsAsFoundForReview() {
        Truth t = truth(new Truth.Item("DATE_OF_BIRTH", new double[]{0.5, 0.1, 0.1, 0.02}, true));
        Scorer.Score s = Scorer.score(t, List.of(new Prediction(1, "DATE_OF_BIRTH", null, "x")), p -> true, ALL);
        assertEquals(1, s.total.found);
        assertEquals(1, s.total.foundWithoutBox);
        assertEquals(1, s.handwritten.found);
    }

    @Test
    void aSecondBoxOnTheSameItemIsADuplicateNotAFalseFlag() {
        Truth t = truth(new Truth.Item("SSN", new double[]{0.5, 0.1, 0.1, 0.02}, null));
        Prediction a = new Prediction(1, "SSN", new double[]{0.5, 0.1, 0.1, 0.02}, "x");
        Prediction b = new Prediction(1, "SSN", new double[]{0.52, 0.1, 0.06, 0.02}, "y");
        Scorer.Score s = Scorer.score(t, List.of(a, b), p -> true, ALL);
        assertEquals(1, s.total.found);
        assertEquals(1, s.total.duplicates);
        assertEquals(0, s.total.falseFlags);
    }

    @Test
    void pageAndTypeFiltersLimitWhatCounts() {
        Truth t = new Truth(1, List.of(
                new Truth.PageTruth(1, "TYPED", null, List.of(new Truth.Item("SSN", new double[]{0.1, 0.1, 0.1, 0.02}, null))),
                new Truth.PageTruth(2, "ID", null, List.of(new Truth.Item("GOVERNMENT_ID", new double[]{0.1, 0.1, 0.1, 0.02}, null)))));
        Scorer.Score onlyPage2 = Scorer.score(t, List.of(), p -> p == 2, ALL);
        assertEquals(1, onlyPage2.total.expected);
        assertEquals(1, onlyPage2.byKind.get("ID").expected);
        Scorer.Score textTypes = Scorer.score(t, List.of(), p -> true, Truth.TEXT_PATH_TYPES);
        assertEquals(1, textTypes.total.expected);
    }

    @Test
    void overlapIsMeasuredAgainstTheSmallerBox() {
        assertEquals(1.0, Scorer.overlap(new double[]{0.1, 0.1, 0.2, 0.2}, new double[]{0.15, 0.15, 0.05, 0.05}), 1e-9);
        assertEquals(0.0, Scorer.overlap(new double[]{0.1, 0.1, 0.1, 0.1}, new double[]{0.3, 0.3, 0.1, 0.1}), 1e-9);
        assertTrue(Scorer.boxesMatch(new double[]{0.1, 0.1, 0.1, 0.02}, new double[]{0.08, 0.09, 0.14, 0.04}));
        assertFalse(Scorer.boxesMatch(new double[]{0.1, 0.1, 0.1, 0.02}, new double[]{0.3, 0.1, 0.1, 0.02}));
    }
}
