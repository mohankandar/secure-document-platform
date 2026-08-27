package com.securedoc.platform.domain.policy;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.PolicyId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root for policy management.
 * A policy owns multiple versions, with the latest version being the active one.
 */
public final class Policy {

    private final PolicyId id;
    private final TenantId tenantId;
    private final String name;
    private final String description;
    private final Instant createdAt;
    private final List<PolicyVersion> versions;
    private boolean active;
    private Instant updatedAt;

    private Policy(PolicyId id, TenantId tenantId, String name, String description, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.description = description;
        this.createdAt = createdAt;
        this.versions = new ArrayList<>();
        this.active = true;
        this.updatedAt = createdAt;
    }

    public static Policy create(TenantId tenantId, String name, String description) {
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(name, "Policy name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Policy name must not be blank");
        }

        return new Policy(PolicyId.generate(), tenantId, name, description, Instant.now());
    }

    public static Policy reconstitute(PolicyId id, TenantId tenantId, String name, String description,
                                       Instant createdAt, List<PolicyVersion> versions,
                                       boolean active, Instant updatedAt) {
        Policy policy = new Policy(id, tenantId, name, description, createdAt);
        policy.versions.addAll(versions);
        policy.active = active;
        policy.updatedAt = updatedAt;
        return policy;
    }

    /**
     * Publishes a new version with the given rules.
     */
    public PolicyVersion publishVersion(ActorId createdBy, List<PolicyRule> rules, String versionDescription) {
        if (!active) {
            throw new IllegalStateException("Cannot publish version for inactive policy");
        }
        int nextVersion = versions.size() + 1;
        PolicyVersion version = PolicyVersion.create(nextVersion, createdBy, rules, versionDescription);
        versions.add(version);
        updatedAt = Instant.now();
        return version;
    }

    /**
     * Returns the current (latest) policy version, or empty if no versions exist.
     */
    public Optional<PolicyVersion> currentVersion() {
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(versions.get(versions.size() - 1));
    }

    public void deactivate() {
        if (!active) {
            throw new IllegalStateException("Policy is already inactive");
        }
        active = false;
        updatedAt = Instant.now();
    }

    public void activate() {
        if (active) {
            throw new IllegalStateException("Policy is already active");
        }
        active = true;
        updatedAt = Instant.now();
    }

    // --- Accessors ---

    public PolicyId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<PolicyVersion> versions() {
        return Collections.unmodifiableList(versions);
    }

    public boolean isActive() {
        return active;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public int versionCount() {
        return versions.size();
    }
}
