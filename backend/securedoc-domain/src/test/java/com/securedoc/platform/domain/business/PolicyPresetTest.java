package com.securedoc.platform.domain.business;

import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Task 0016 A3: the one shared preset rule, including at its edges. */
class PolicyPresetTest {

    @ParameterizedTest(name = "{0} at {1} is {2}")
    @CsvSource({
            "REVIEW_ALL,     0.80, STRICT",
            "REVIEW_ALL,     0.70, STRICT",
            "REVIEW_ALL,     0.95, STRICT",
            "REVIEW_ALL,     0.50, STRICT",
            "UNCERTAIN_ONLY, 0.80, BALANCED",
            "UNCERTAIN_ONLY, 0.79, CUSTOM",
            "UNCERTAIN_ONLY, 0.81, CUSTOM",
            "UNCERTAIN_ONLY, 0.70, LIGHT",
            "UNCERTAIN_ONLY, 0.69, CUSTOM",
            "UNCERTAIN_ONLY, 0.71, CUSTOM",
            "UNCERTAIN_ONLY, 0.75, CUSTOM",
            "UNCERTAIN_ONLY, 0.85, CUSTOM",
            "UNCERTAIN_ONLY, 0.50, CUSTOM",
            "UNCERTAIN_ONLY, 0.99, CUSTOM",
            "UNCERTAIN_ONLY, 0.801, CUSTOM",
            "UNCERTAIN_ONLY, 0.799, CUSTOM"
    })
    void presetFromReviewModeAndThreshold(ReviewMode mode, double threshold, PolicyPreset expected) {
        assertEquals(expected, PolicyPreset.of(mode, threshold));
    }

    @Test
    void floatingPointNoiseAroundASavedValueStillMatches() {
        assertEquals(PolicyPreset.BALANCED, PolicyPreset.of(ReviewMode.UNCERTAIN_ONLY, 0.1 * 8));
        assertEquals(PolicyPreset.LIGHT, PolicyPreset.of(ReviewMode.UNCERTAIN_ONLY, 0.1 * 7));
    }

    @Test
    void todaysDefaultPolicyIsBalanced() {
        assertEquals(PolicyPreset.BALANCED, PolicyPreset.of(TenantRedactionPolicy.defaults(TenantId.generate())));
    }

    @Test
    void sensitiveTypesAndTheSsnSwitchDoNotChangeThePreset() {
        TenantRedactionPolicy oneType = new TenantRedactionPolicy(TenantId.generate(), Set.of(EntityType.SSN), 0.70,
                ReviewMode.UNCERTAIN_ONLY, false, Instant.now());
        assertEquals(PolicyPreset.LIGHT, PolicyPreset.of(oneType));
    }

    @Test
    void reviewModeIsRequired() {
        assertThrows(NullPointerException.class, () -> PolicyPreset.of(null, 0.80));
    }
}
