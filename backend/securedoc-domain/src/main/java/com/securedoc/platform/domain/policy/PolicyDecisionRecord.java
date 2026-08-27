package com.securedoc.platform.domain.policy;

import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.PolicyVersionId;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable record of a policy decision made against a finding.
 * Captures the rule that matched and the resulting decision for audit trail.
 */
public record PolicyDecisionRecord(
        FindingId findingId,
        PolicyVersionId policyVersionId,
        String ruleName,
        PolicyDecision decision,
        Instant evaluatedAt
) {

    public PolicyDecisionRecord {
        Objects.requireNonNull(findingId, "FindingId must not be null");
        Objects.requireNonNull(policyVersionId, "PolicyVersionId must not be null");
        Objects.requireNonNull(ruleName, "Rule name must not be null");
        Objects.requireNonNull(decision, "Decision must not be null");
        Objects.requireNonNull(evaluatedAt, "EvaluatedAt must not be null");
    }

    public static PolicyDecisionRecord of(FindingId findingId, PolicyVersionId policyVersionId,
                                           String ruleName, PolicyDecision decision) {
        return new PolicyDecisionRecord(findingId, policyVersionId, ruleName, decision, Instant.now());
    }
}
