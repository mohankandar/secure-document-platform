package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "package_processing_intelligence_snapshots")
public class PackageProcessingIntelligenceSnapshotEntity {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "processing_cycle", nullable = false)
    private int processingCycle;

    @Column(name = "requested_mode", nullable = false, length = 24)
    private String requestedMode;

    @Column(name = "effective_mode", nullable = false, length = 24)
    private String effectiveMode;

    @Column(name = "bundle_version", nullable = false, length = 80)
    private String bundleVersion;

    @Column(name = "policy_configuration_version", nullable = false)
    private int policyConfigurationVersion;

    @Column(name = "intelligence_execution_allowed", nullable = false)
    private boolean intelligenceExecutionAllowed;

    @Column(name = "decision_influence_allowed", nullable = false)
    private boolean decisionInfluenceAllowed;

    @Column(name = "baseline_discovery_required", nullable = false)
    private boolean baselineDiscoveryRequired;

    @Column(name = "fallback_to_baseline", nullable = false)
    private boolean fallbackToBaseline;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getPackageId() { return packageId; }
    public void setPackageId(UUID packageId) { this.packageId = packageId; }
    public int getProcessingCycle() { return processingCycle; }
    public void setProcessingCycle(int processingCycle) { this.processingCycle = processingCycle; }
    public String getRequestedMode() { return requestedMode; }
    public void setRequestedMode(String requestedMode) { this.requestedMode = requestedMode; }
    public String getEffectiveMode() { return effectiveMode; }
    public void setEffectiveMode(String effectiveMode) { this.effectiveMode = effectiveMode; }
    public String getBundleVersion() { return bundleVersion; }
    public void setBundleVersion(String bundleVersion) { this.bundleVersion = bundleVersion; }
    public int getPolicyConfigurationVersion() { return policyConfigurationVersion; }
    public void setPolicyConfigurationVersion(int policyConfigurationVersion) { this.policyConfigurationVersion = policyConfigurationVersion; }
    public boolean isIntelligenceExecutionAllowed() { return intelligenceExecutionAllowed; }
    public void setIntelligenceExecutionAllowed(boolean intelligenceExecutionAllowed) { this.intelligenceExecutionAllowed = intelligenceExecutionAllowed; }
    public boolean isDecisionInfluenceAllowed() { return decisionInfluenceAllowed; }
    public void setDecisionInfluenceAllowed(boolean decisionInfluenceAllowed) { this.decisionInfluenceAllowed = decisionInfluenceAllowed; }
    public boolean isBaselineDiscoveryRequired() { return baselineDiscoveryRequired; }
    public void setBaselineDiscoveryRequired(boolean baselineDiscoveryRequired) { this.baselineDiscoveryRequired = baselineDiscoveryRequired; }
    public boolean isFallbackToBaseline() { return fallbackToBaseline; }
    public void setFallbackToBaseline(boolean fallbackToBaseline) { this.fallbackToBaseline = fallbackToBaseline; }
    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant capturedAt) { this.capturedAt = capturedAt; }
}
