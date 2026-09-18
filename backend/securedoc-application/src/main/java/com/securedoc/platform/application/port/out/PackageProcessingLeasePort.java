package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Distributed lease boundary for asynchronous package processing.
 *
 * <p>The lease store is authoritative for one active processing owner per package. PostgreSQL
 * implementations use database time for expiry so node clock skew cannot create split ownership.
 */
public interface PackageProcessingLeasePort {

    Optional<LeaseSnapshot> acquireOrRenew(PackageId packageId,
                                           TenantId tenantId,
                                           String ownerId,
                                           Duration leaseDuration);

    Optional<LeaseSnapshot> current(PackageId packageId);

    /** Returns expired leases ordered oldest-first according to the shared lease-store clock. */
    List<LeaseSnapshot> findExpired(int limit);

    Instant currentTime();

    boolean isCurrent(PackageId packageId, String ownerId, long fencingToken);

    boolean release(PackageId packageId, String ownerId, long fencingToken);

    record LeaseSnapshot(
            PackageId packageId,
            TenantId tenantId,
            String ownerId,
            long fencingToken,
            Instant acquiredAt,
            Instant heartbeatAt,
            Instant expiresAt) {
    }
}
