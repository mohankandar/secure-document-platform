package com.securedoc.platform.domain.vault;

import com.securedoc.platform.domain.identity.AccessRequestId;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.identity.VaultEntryId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Entity representing a request to access an original/restricted artifact in the vault.
 * Tracks the request lifecycle from submission through approval/denial.
 */
public final class AccessRequest {

    private final AccessRequestId id;
    private final TenantId tenantId;
    private final VaultEntryId vaultEntryId;
    private final ArtifactId artifactId;
    private final ActorId requestedBy;
    private final String justification;
    private final Instant requestedAt;
    private final Instant expiresAt;
    private AccessRequestStatus status;
    private ActorId decidedBy;
    private String decisionReason;
    private Instant decidedAt;

    private AccessRequest(AccessRequestId id, TenantId tenantId, VaultEntryId vaultEntryId,
                          ArtifactId artifactId, ActorId requestedBy, String justification,
                          Instant requestedAt, Instant expiresAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.vaultEntryId = vaultEntryId;
        this.artifactId = artifactId;
        this.requestedBy = requestedBy;
        this.justification = justification;
        this.requestedAt = requestedAt;
        this.expiresAt = expiresAt;
        this.status = AccessRequestStatus.PENDING;
    }

    public static AccessRequest create(TenantId tenantId, VaultEntryId vaultEntryId,
                                        ArtifactId artifactId, ActorId requestedBy,
                                        String justification, Instant expiresAt) {
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(vaultEntryId, "VaultEntryId must not be null");
        Objects.requireNonNull(artifactId, "ArtifactId must not be null");
        Objects.requireNonNull(requestedBy, "RequestedBy must not be null");
        Objects.requireNonNull(justification, "Justification must not be null");
        Objects.requireNonNull(expiresAt, "ExpiresAt must not be null");
        if (justification.isBlank()) {
            throw new IllegalArgumentException("Justification must not be blank");
        }

        Instant now = Instant.now();
        if (expiresAt.isBefore(now)) {
            throw new IllegalArgumentException("Expiry must be in the future");
        }

        return new AccessRequest(AccessRequestId.generate(), tenantId, vaultEntryId,
                artifactId, requestedBy, justification, now, expiresAt);
    }

    public static AccessRequest reconstitute(AccessRequestId id, TenantId tenantId,
                                              VaultEntryId vaultEntryId, ArtifactId artifactId,
                                              ActorId requestedBy, String justification,
                                              Instant requestedAt, Instant expiresAt,
                                              AccessRequestStatus status, ActorId decidedBy,
                                              String decisionReason, Instant decidedAt) {
        AccessRequest request = new AccessRequest(id, tenantId, vaultEntryId, artifactId,
                requestedBy, justification, requestedAt, expiresAt);
        request.status = status;
        request.decidedBy = decidedBy;
        request.decisionReason = decisionReason;
        request.decidedAt = decidedAt;
        return request;
    }

    public void approve(ActorId approver, String reason) {
        Objects.requireNonNull(approver, "Approver must not be null");
        if (status != AccessRequestStatus.PENDING) {
            throw new IllegalStateException("Cannot approve request in status: " + status);
        }
        if (isExpired()) {
            throw new IllegalStateException("Cannot approve expired request");
        }
        if (approver.equals(requestedBy)) {
            throw new IllegalStateException("Requester cannot approve their own access request");
        }
        this.decidedBy = approver;
        this.decisionReason = reason;
        this.decidedAt = Instant.now();
        this.status = AccessRequestStatus.APPROVED;
    }

    public void deny(ActorId denier, String reason) {
        Objects.requireNonNull(denier, "Denier must not be null");
        Objects.requireNonNull(reason, "Denial reason must not be null");
        if (status != AccessRequestStatus.PENDING) {
            throw new IllegalStateException("Cannot deny request in status: " + status);
        }
        this.decidedBy = denier;
        this.decisionReason = reason;
        this.decidedAt = Instant.now();
        this.status = AccessRequestStatus.DENIED;
    }

    public void revoke(ActorId revoker, String reason) {
        Objects.requireNonNull(revoker, "Revoker must not be null");
        if (status != AccessRequestStatus.APPROVED) {
            throw new IllegalStateException("Cannot revoke request in status: " + status);
        }
        this.decidedBy = revoker;
        this.decisionReason = reason;
        this.decidedAt = Instant.now();
        this.status = AccessRequestStatus.REVOKED;
    }

    public void expire() {
        if (status != AccessRequestStatus.PENDING) {
            throw new IllegalStateException("Cannot expire request in status: " + status);
        }
        this.status = AccessRequestStatus.EXPIRED;
        this.decidedAt = Instant.now();
    }

    public boolean isExpired() {
        return status == AccessRequestStatus.PENDING && Instant.now().isAfter(expiresAt);
    }

    /**
     * Whether an approved access grant has expired.
     * An approved request is considered expired when the current time exceeds expiresAt.
     */
    public boolean isAccessExpired() {
        return status == AccessRequestStatus.APPROVED && Instant.now().isAfter(expiresAt);
    }

    // --- Accessors ---

    public AccessRequestId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public VaultEntryId vaultEntryId() {
        return vaultEntryId;
    }

    public ArtifactId artifactId() {
        return artifactId;
    }

    public ActorId requestedBy() {
        return requestedBy;
    }

    public String justification() {
        return justification;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public AccessRequestStatus status() {
        return status;
    }

    public Optional<ActorId> decidedBy() {
        return Optional.ofNullable(decidedBy);
    }

    public Optional<String> decisionReason() {
        return Optional.ofNullable(decisionReason);
    }

    public Optional<Instant> decidedAt() {
        return Optional.ofNullable(decidedAt);
    }
}
