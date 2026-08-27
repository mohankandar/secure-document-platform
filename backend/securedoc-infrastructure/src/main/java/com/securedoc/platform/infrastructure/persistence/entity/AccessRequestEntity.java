package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "access_requests")
public class AccessRequestEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "vault_entry_id", nullable = false)
    private UUID vaultEntryId;

    @Column(name = "artifact_id", nullable = false)
    private UUID artifactId;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "justification", nullable = false, length = 500)
    private String justification;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public AccessRequestEntity() {
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getVaultEntryId() { return vaultEntryId; }
    public void setVaultEntryId(UUID vaultEntryId) { this.vaultEntryId = vaultEntryId; }

    public UUID getArtifactId() { return artifactId; }
    public void setArtifactId(UUID artifactId) { this.artifactId = artifactId; }

    public UUID getRequestedBy() { return requestedBy; }
    public void setRequestedBy(UUID requestedBy) { this.requestedBy = requestedBy; }

    public String getJustification() { return justification; }
    public void setJustification(String justification) { this.justification = justification; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public UUID getDecidedBy() { return decidedBy; }
    public void setDecidedBy(UUID decidedBy) { this.decidedBy = decidedBy; }

    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }

    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
