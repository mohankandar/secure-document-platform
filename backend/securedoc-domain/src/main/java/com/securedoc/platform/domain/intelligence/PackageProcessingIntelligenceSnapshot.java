package com.securedoc.platform.domain.intelligence;

import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable immutable snapshot of the intelligence policy/bundle selected for one package processing cycle.
 * This is safe support evidence and contains no customer document content.
 */
public record PackageProcessingIntelligenceSnapshot(
        UUID id,
        TenantId tenantId,
        PackageId packageId,
        int processingCycle,
        DocumentIntelligenceMode requestedMode,
        DocumentIntelligenceMode effectiveMode,
        String bundleVersion,
        int policyConfigurationVersion,
        boolean intelligenceExecutionAllowed,
        boolean decisionInfluenceAllowed,
        boolean baselineDiscoveryRequired,
        boolean fallbackToBaseline,
        Instant capturedAt) {

    public PackageProcessingIntelligenceSnapshot {
        Objects.requireNonNull(id, "Snapshot id is required");
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(packageId, "Package id is required");
        if (processingCycle < 1) throw new IllegalArgumentException("Processing cycle must be >= 1");
        Objects.requireNonNull(requestedMode, "Requested intelligence mode is required");
        Objects.requireNonNull(effectiveMode, "Effective intelligence mode is required");
        if (bundleVersion == null || bundleVersion.trim().isEmpty()) throw new IllegalArgumentException("Bundle version is required");
        bundleVersion = bundleVersion.trim();
        if (policyConfigurationVersion < 1) throw new IllegalArgumentException("Policy configuration version must be >= 1");
        if (!baselineDiscoveryRequired) {
            throw new IllegalArgumentException("V1 requires baseline discovery to remain enabled");
        }
        if (decisionInfluenceAllowed && effectiveMode != DocumentIntelligenceMode.ENABLED) {
            throw new IllegalArgumentException("Decision influence is allowed only in ENABLED mode");
        }
        if (intelligenceExecutionAllowed != effectiveMode.executionAllowed()) {
            throw new IllegalArgumentException("Intelligence execution flag must match effective mode");
        }
        Objects.requireNonNull(capturedAt, "Snapshot captured time is required");
    }

    public static PackageProcessingIntelligenceSnapshot capture(
            TenantId tenantId,
            PackageId packageId,
            int processingCycle,
            TenantDocumentIntelligencePolicy policy,
            boolean activeBundleExecutable,
            Instant now) {
        Objects.requireNonNull(policy);
        boolean fallback = policy.mode() != DocumentIntelligenceMode.DISABLED && !activeBundleExecutable;
        DocumentIntelligenceMode effectiveMode = fallback ? DocumentIntelligenceMode.DISABLED : policy.mode();
        String bundleVersion = (policy.mode() == DocumentIntelligenceMode.DISABLED || fallback)
                ? DocumentIntelligenceBundle.R11_BASELINE_VERSION
                : policy.activeBundleVersion();
        return new PackageProcessingIntelligenceSnapshot(UUID.randomUUID(), tenantId, packageId, processingCycle,
                policy.mode(), effectiveMode, bundleVersion, policy.configurationVersion(),
                effectiveMode.executionAllowed(), effectiveMode.decisionInfluenceAllowed(), true, fallback, now);
    }
}
