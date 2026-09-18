package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_document_intelligence_policies")
public class TenantDocumentIntelligencePolicyEntity {
    @Id
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "mode", nullable = false, length = 24)
    private String mode;

    @Column(name = "active_bundle_version", nullable = false, length = 80)
    private String activeBundleVersion;

    @Column(name = "last_known_good_bundle_version", nullable = false, length = 80)
    private String lastKnownGoodBundleVersion;

    @Column(name = "configuration_version", nullable = false)
    private int configurationVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getActiveBundleVersion() { return activeBundleVersion; }
    public void setActiveBundleVersion(String activeBundleVersion) { this.activeBundleVersion = activeBundleVersion; }
    public String getLastKnownGoodBundleVersion() { return lastKnownGoodBundleVersion; }
    public void setLastKnownGoodBundleVersion(String lastKnownGoodBundleVersion) { this.lastKnownGoodBundleVersion = lastKnownGoodBundleVersion; }
    public int getConfigurationVersion() { return configurationVersion; }
    public void setConfigurationVersion(int configurationVersion) { this.configurationVersion = configurationVersion; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
