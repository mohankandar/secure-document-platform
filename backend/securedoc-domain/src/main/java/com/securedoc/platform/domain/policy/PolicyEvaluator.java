package com.securedoc.platform.domain.policy;

import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.vo.Confidence;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Domain service that evaluates a set of policy rules against findings.
 * Stateless — operates on the provided policy version's rules.
 */
public final class PolicyEvaluator {

    private PolicyEvaluator() {
        // Utility class
    }

    /**
     * Evaluates a single finding against the given policy version.
     * Returns the decision from the highest-priority matching rule, or INDETERMINATE if no rule matches.
     */
    public static PolicyDecisionRecord evaluate(Finding finding, PolicyVersion policyVersion) {
        Objects.requireNonNull(finding, "Finding must not be null");
        Objects.requireNonNull(policyVersion, "PolicyVersion must not be null");

        EntityType entityType = finding.entityType();
        Confidence confidence = finding.confidence();

        Optional<PolicyRule> matchingRule = policyVersion.rules().stream()
                .filter(rule -> rule.appliesTo(entityType, confidence))
                .min(Comparator.comparingInt(PolicyRule::priority));

        PolicyDecision decision = matchingRule
                .map(PolicyRule::decision)
                .orElse(PolicyDecision.INDETERMINATE);

        String ruleName = matchingRule
                .map(PolicyRule::ruleName)
                .orElse("no-match");

        return PolicyDecisionRecord.of(finding.id(), policyVersion.id(), ruleName, decision);
    }

    /**
     * Evaluates multiple findings against a policy version.
     */
    public static List<PolicyDecisionRecord> evaluateAll(List<Finding> findings, PolicyVersion policyVersion) {
        Objects.requireNonNull(findings, "Findings must not be null");
        Objects.requireNonNull(policyVersion, "PolicyVersion must not be null");

        return findings.stream()
                .map(finding -> evaluate(finding, policyVersion))
                .toList();
    }
}
