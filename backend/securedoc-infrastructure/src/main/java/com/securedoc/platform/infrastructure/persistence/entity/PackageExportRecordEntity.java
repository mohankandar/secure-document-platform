package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "package_export_records")
public class PackageExportRecordEntity {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;
    @Column(name = "package_id", nullable = false)
    private UUID packageId;
    @Column(name = "cycle_number", nullable = false)
    private int cycleNumber;
    @Column(name = "destination_type", nullable = false)
    private String destinationType;
    @Column(name = "exported_at", nullable = false)
    private Instant exportedAt;
    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getPackageId() { return packageId; }
    public void setPackageId(UUID packageId) { this.packageId = packageId; }
    public int getCycleNumber() { return cycleNumber; }
    public void setCycleNumber(int value) { this.cycleNumber = value; }
    public String getDestinationType() { return destinationType; }
    public void setDestinationType(String value) { this.destinationType = value; }
    public Instant getExportedAt() { return exportedAt; }
    public void setExportedAt(Instant value) { this.exportedAt = value; }
    public UUID getCorrelationId() { return correlationId; }
    public void setCorrelationId(UUID value) { this.correlationId = value; }
}
