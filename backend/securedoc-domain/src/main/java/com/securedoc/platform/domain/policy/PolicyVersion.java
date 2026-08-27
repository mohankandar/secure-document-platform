package com.securedoc.platform.domain.policy;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.PolicyVersionId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A versioned snapshot of policy rules.
 * Policy versions are immutable once published — changes create new versions.
 */
public final class PolicyVersion {

    private final PolicyVersionId id;
    private final int versionNumber;
    private final ActorId createdBy;
    private final Instant createdAt;
    private final List<PolicyRule> rules;
    private final String description;

    private PolicyVersion(PolicyVersionId id, int versionNumber, ActorId createdBy,
                          Instant createdAt, List<PolicyRule> rules, String description) {
        this.id = id;
        this.versionNumber = versionNumber;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.rules = new ArrayList<>(rules);
        this.description = description;
    }

    public static PolicyVersion create(int versionNumber, ActorId createdBy,
                                        List<PolicyRule> rules, String description) {
        Objects.requireNonNull(createdBy, "CreatedBy must not be null");
        Objects.requireNonNull(rules, "Rules must not be null");
        if (versionNumber < 1) {
            throw new IllegalArgumentException("Version number must be >= 1, got: " + versionNumber);
        }
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("Policy version must have at least one rule");
        }

        return new PolicyVersion(PolicyVersionId.generate(), versionNumber, createdBy,
                Instant.now(), rules, description);
    }

    public static PolicyVersion reconstitute(PolicyVersionId id, int versionNumber, ActorId createdBy,
                                              Instant createdAt, List<PolicyRule> rules, String description) {
        return new PolicyVersion(id, versionNumber, createdBy, createdAt, rules, description);
    }

    public int ruleCount() {
        return rules.size();
    }

    // --- Accessors ---

    public PolicyVersionId id() {
        return id;
    }

    public int versionNumber() {
        return versionNumber;
    }

    public ActorId createdBy() {
        return createdBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<PolicyRule> rules() {
        return Collections.unmodifiableList(rules);
    }

    public String description() {
        return description;
    }
}
