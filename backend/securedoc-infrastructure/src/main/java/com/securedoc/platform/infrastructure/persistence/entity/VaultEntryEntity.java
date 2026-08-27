package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vault_entries")
public class VaultEntryEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "artifact_id", nullable = false)
    private UUID artifactId;

    @Column(name = "artifact_type", nullable = false)
    private String artifactType;

    @Column(name = "checksum_algorithm", nullable = false)
    private String checksumAlgorithm;

    @Column(name = "checksum_value", nullable = false)
    private String checksumValue;

    @Column(name = "retention_min_days", nullable = false)
    private long retentionMinDays;

    @Column(name = "retention_max_days")
    private Long retentionMaxDays;

    @Column(name = "legal_hold", nullable = false)
    private boolean legalHold;

    @Column(name = "sealed", nullable = false)
    private boolean sealed;

    @Column(name = "sealed_at")
    private Instant sealedAt;

    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public VaultEntryEntity() {
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getPackageId() { return packageId; }
    public void setPackageId(UUID packageId) { this.packageId = packageId; }

    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }

    public UUID getArtifactId() { return artifactId; }
    public void setArtifactId(UUID artifactId) { this.artifactId = artifactId; }

    public String getArtifactType() { return artifactType; }
    public void setArtifactType(String artifactType) { this.artifactType = artifactType; }

    public String getChecksumAlgorithm() { return checksumAlgorithm; }
    public void setChecksumAlgorithm(String checksumAlgorithm) { this.checksumAlgorithm = checksumAlgorithm; }

    public String getChecksumValue() { return checksumValue; }
    public void setChecksumValue(String checksumValue) { this.checksumValue = checksumValue; }

    public long getRetentionMinDays() { return retentionMinDays; }
    public void setRetentionMinDays(long retentionMinDays) { this.retentionMinDays = retentionMinDays; }

    public Long getRetentionMaxDays() { return retentionMaxDays; }
    public void setRetentionMaxDays(Long retentionMaxDays) { this.retentionMaxDays = retentionMaxDays; }

    public boolean isLegalHold() { return legalHold; }
    public void setLegalHold(boolean legalHold) { this.legalHold = legalHold; }

    public boolean isSealed() { return sealed; }
    public void setSealed(boolean sealed) { this.sealed = sealed; }

    public Instant getSealedAt() { return sealedAt; }
    public void setSealedAt(Instant sealedAt) { this.sealedAt = sealedAt; }

    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
