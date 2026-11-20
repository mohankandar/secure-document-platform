package com.securedoc.platform.application.business.admin;

import com.securedoc.platform.application.port.out.PolicyEstimateReadPort;
import com.securedoc.platform.application.port.out.PolicyEstimateReadPort.Bucket;
import com.securedoc.platform.application.port.out.PolicyEstimateReadPort.Facts;
import com.securedoc.platform.domain.business.ReviewMode;

import java.util.Objects;

/**
 * When a finding and a case go to a person (task 0019 B1), as {@code SensitiveDataDetectionService} and
 * {@code TriggerPackageProcessingService} decide it. Read from that code, never changed by this class:
 *
 * <ul>
 *   <li>a detected finding is confirmed automatically only under {@link ReviewMode#UNCERTAIN_ONLY}, when its
 *       confidence is at or above the threshold <em>and</em> it has a bounding box; every other finding waits for a
 *       person (unlocated AI findings and OCR-guard review signals are stored without a bounding box, so they
 *       always wait);</li>
 *   <li>a case goes to a person when at least one of its findings waits. A case with no findings goes straight
 *       through, under {@link ReviewMode#REVIEW_ALL} too.</li>
 * </ul>
 * The estimate applies this one rule to both the saved and the proposed policy.
 */
public final class PolicyEstimateRule {

    private PolicyEstimateRule() { }

    /** One finding: does it wait for a person? */
    public static boolean findingGoesToPerson(ReviewMode mode, double threshold, double confidence, boolean located) {
        Objects.requireNonNull(mode, "mode");
        boolean autoConfirmed = mode == ReviewMode.UNCERTAIN_ONLY && confidence >= threshold && located;
        return !autoConfirmed;
    }

    /** A finding by its effective confidence ({@link PolicyEstimateReadPort#UNLOCATED} when it has no box). */
    static boolean goesToPerson(ReviewMode mode, double threshold, double effectiveConfidence) {
        boolean located = effectiveConfidence != PolicyEstimateReadPort.UNLOCATED;
        return findingGoesToPerson(mode, threshold, effectiveConfidence, located);
    }

    /** Findings a person would check. */
    public static long findingsToPerson(Facts facts, ReviewMode mode, double threshold) {
        long n = 0;
        for (Bucket b : facts.findings()) if (goesToPerson(mode, threshold, b.value())) n += b.count();
        return n;
    }

    /**
     * Cases a person would check: a case waits when any finding waits. Under {@code UNCERTAIN_ONLY} that is exactly
     * when its lowest effective confidence waits (an unlocated finding is the lowest); under {@code REVIEW_ALL} every
     * case with a finding waits.
     */
    public static long casesToPerson(Facts facts, ReviewMode mode, double threshold) {
        long n = 0;
        for (Bucket b : facts.caseLowest()) if (goesToPerson(mode, threshold, b.value())) n += b.count();
        return n;
    }
}
