package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "external_intakes", uniqueConstraints = @UniqueConstraint(
        name = "uk_external_intake_source_revision_attempt",
        columnNames = {"tenant_id", "integration_connection_id", "external_source_package_id", "source_revision", "attempt_number"}))
public class ExternalIntakeEntity {
    @Id @Column(name = "package_id", nullable = false) private UUID packageId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "integration_connection_id", nullable = false) private UUID integrationConnectionId;
    @Column(name = "channel", nullable = false, length = 32) private String channel;
    @Column(name = "external_source_package_id", nullable = false, length = 255) private String externalSourcePackageId;
    @Column(name = "source_revision", nullable = false, length = 64) private String sourceRevision;
    @Column(name = "attempt_number", nullable = false) private int attemptNumber;
    @Column(name = "request_fingerprint", nullable = false, length = 64) private String requestFingerprint;
    @Column(name = "status", nullable = false, length = 64) private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "accepted_at") private Instant acceptedAt;
    @Column(name = "expired_at") private Instant expiredAt;
    @Column(name = "cleanup_completed_at") private Instant cleanupCompletedAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public UUID getPackageId() { return packageId; } public void setPackageId(UUID v) { packageId = v; }
    public UUID getTenantId() { return tenantId; } public void setTenantId(UUID v) { tenantId = v; }
    public UUID getIntegrationConnectionId() { return integrationConnectionId; } public void setIntegrationConnectionId(UUID v) { integrationConnectionId = v; }
    public String getChannel() { return channel; } public void setChannel(String v) { channel = v; }
    public String getExternalSourcePackageId() { return externalSourcePackageId; } public void setExternalSourcePackageId(String v) { externalSourcePackageId = v; }
    public String getSourceRevision() { return sourceRevision; } public void setSourceRevision(String v) { sourceRevision = v; }
    public int getAttemptNumber() { return attemptNumber; } public void setAttemptNumber(int v) { attemptNumber = v; }
    public String getRequestFingerprint() { return requestFingerprint; } public void setRequestFingerprint(String v) { requestFingerprint = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt = v; }
    public Instant getExpiresAt() { return expiresAt; } public void setExpiresAt(Instant v) { expiresAt = v; }
    public Instant getAcceptedAt() { return acceptedAt; } public void setAcceptedAt(Instant v) { acceptedAt = v; }
    public Instant getExpiredAt() { return expiredAt; } public void setExpiredAt(Instant v) { expiredAt = v; }
    public Instant getCleanupCompletedAt() { return cleanupCompletedAt; } public void setCleanupCompletedAt(Instant v) { cleanupCompletedAt = v; }
    public Instant getUpdatedAt() { return updatedAt; } public void setUpdatedAt(Instant v) { updatedAt = v; }
}
