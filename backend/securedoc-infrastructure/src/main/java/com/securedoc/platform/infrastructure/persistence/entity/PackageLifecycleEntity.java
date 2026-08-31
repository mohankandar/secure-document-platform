package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "package_lifecycles")
public class PackageLifecycleEntity {
    @Id
    @Column(name = "package_id", nullable = false)
    private UUID packageId;
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;
    @Column(name = "successful_export_cycles", nullable = false)
    private int successfulExportCycles;
    @Column(name = "last_exported_at")
    private Instant lastExportedAt;
    @Column(name = "original_delete_at")
    private Instant originalDeleteAt;
    @Column(name = "redacted_delete_at")
    private Instant redactedDeleteAt;
    @Column(name = "technical_hold_until")
    private Instant technicalHoldUntil;
    @Column(name = "redacted_purged_at")
    private Instant redactedPurgedAt;
    @Column(name = "original_purged_at")
    private Instant originalPurgedAt;
    @Column(name = "purged_at")
    private Instant purgedAt;
    @Column(name = "final_approval_granted", nullable = false)
    private boolean finalApprovalGranted;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public UUID getPackageId() { return packageId; }
    public void setPackageId(UUID packageId) { this.packageId = packageId; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public int getSuccessfulExportCycles() { return successfulExportCycles; }
    public void setSuccessfulExportCycles(int value) { this.successfulExportCycles = value; }
    public Instant getLastExportedAt() { return lastExportedAt; }
    public void setLastExportedAt(Instant value) { this.lastExportedAt = value; }
    public Instant getOriginalDeleteAt() { return originalDeleteAt; }
    public void setOriginalDeleteAt(Instant value) { this.originalDeleteAt = value; }
    public Instant getRedactedDeleteAt() { return redactedDeleteAt; }
    public void setRedactedDeleteAt(Instant value) { this.redactedDeleteAt = value; }
    public Instant getTechnicalHoldUntil() { return technicalHoldUntil; }
    public void setTechnicalHoldUntil(Instant value) { this.technicalHoldUntil = value; }
    public Instant getRedactedPurgedAt() { return redactedPurgedAt; }
    public void setRedactedPurgedAt(Instant value) { this.redactedPurgedAt = value; }
    public Instant getOriginalPurgedAt() { return originalPurgedAt; }
    public void setOriginalPurgedAt(Instant value) { this.originalPurgedAt = value; }
    public Instant getPurgedAt() { return purgedAt; }
    public void setPurgedAt(Instant value) { this.purgedAt = value; }
    public boolean isFinalApprovalGranted() { return finalApprovalGranted; }
    public void setFinalApprovalGranted(boolean value) { this.finalApprovalGranted = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { this.updatedAt = value; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
