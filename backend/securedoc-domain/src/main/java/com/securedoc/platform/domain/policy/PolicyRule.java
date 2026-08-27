package com.securedoc.platform.domain.policy;

import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.vo.Confidence;

import java.util.Objects;

/**
 * A single rule within a policy version that maps entity type + confidence threshold
 * to a decision action.
 */
public record PolicyRule(
        String ruleName,
        EntityType entityType,
        Confidence confidenceThreshold,
        PolicyDecision decision,
        int priority
) {

    public PolicyRule {
        Objects.requireNonNull(ruleName, "Rule name must not be null");
        Objects.requireNonNull(entityType, "EntityType must not be null");
        Objects.requireNonNull(confidenceThreshold, "Confidence threshold must not be null");
        Objects.requireNonNull(decision, "Decision must not be null");
        if (ruleName.isBlank()) {
            throw new IllegalArgumentException("Rule name must not be blank");
        }
        if (priority < 0) {
            throw new IllegalArgumentException("Priority must be >= 0, got: " + priority);
        }
    }

    public static PolicyRule of(String name, EntityType entityType,
                                 Confidence threshold, PolicyDecision decision, int priority) {
        return new PolicyRule(name, entityType, threshold, decision, priority);
    }

    /**
     * Determines if this rule applies to the given entity type and confidence.
     */
    public boolean appliesTo(EntityType type, Confidence confidence) {
        return this.entityType == type && confidence.isAboveThreshold(confidenceThreshold.value());
    }
}
