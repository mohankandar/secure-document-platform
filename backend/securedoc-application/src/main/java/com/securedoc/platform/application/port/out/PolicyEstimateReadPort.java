package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.List;

/**
 * Read-only facts for the policy estimate (task 0019 B2). Scoped to one tenant (the entity the person is signed in
 * to) and to the cases (packages) received in {@code [since, until)}. Policy-free on purpose: the review mode and
 * threshold are applied afterwards by {@code PolicyEstimateRule}, so the saved policy and the proposed one are
 * worked out by the same code from the same facts.
 *
 * <p>Only counts, confidences and whether a finding is located are read: never found text, files, file names or
 * business identifiers. Findings a person drew by hand ({@code MANUAL_FALLBACK}) are left out.
 */
public interface PolicyEstimateReadPort {

    /** The value used for a finding without a bounding box: below any threshold, so it always goes to a person. */
    double UNLOCATED = -1.0;

    Facts facts(TenantId tenantId, Instant since, Instant until);

    /**
     * @param cases     every case received in the window, with or without findings
     * @param findings  the detected findings of those cases by effective confidence: the confidence for a located
     *                  finding, {@link #UNLOCATED} for one without a bounding box
     * @param caseLowest the cases that have at least one detected finding, by their lowest effective confidence
     */
    record Facts(long cases, List<Bucket> findings, List<Bucket> caseLowest) {
        public Facts {
            findings = findings == null ? List.of() : List.copyOf(findings);
            caseLowest = caseLowest == null ? List.of() : List.copyOf(caseLowest);
        }
    }

    /** {@code count} items share the effective confidence {@code value}. */
    record Bucket(double value, long count) { }
}
