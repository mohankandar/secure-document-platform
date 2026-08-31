package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_lifecycle_policies")
public class TenantLifecyclePolicyEntity {
    @Id
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;
    @Column(name = "original_retention_hours", nullable = false)
    private int originalRetentionHours;
    @Column(name = "redacted_cache_hours", nullable = false)
    private int redactedCacheHours;
    @Column(name = "never_exported_hold_hours", nullable = false)
    private int neverExportedHoldHours;
    @Column(name = "final_approval_required", nullable = false)
    private boolean finalApprovalRequired;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public int getOriginalRetentionHours() { return originalRetentionHours; }
    public void setOriginalRetentionHours(int value) { this.originalRetentionHours = value; }
    public int getRedactedCacheHours() { return redactedCacheHours; }
    public void setRedactedCacheHours(int value) { this.redactedCacheHours = value; }
    public int getNeverExportedHoldHours() { return neverExportedHoldHours; }
    public void setNeverExportedHoldHours(int value) { this.neverExportedHoldHours = value; }
    public boolean isFinalApprovalRequired() { return finalApprovalRequired; }
    public void setFinalApprovalRequired(boolean value) { this.finalApprovalRequired = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
