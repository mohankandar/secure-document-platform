package com.securedoc.platform.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Distributed coordination boundary for one active watched-folder scanner lease. */
public interface WatchedFolderLeasePort {
    Optional<LeaseSnapshot> acquireOrRenew(String leaseName, String ownerId, Duration leaseDuration);
    Optional<LeaseSnapshot> current(String leaseName);

    /** Shared coordination time. PostgreSQL implementations must use database time, not the JVM clock. */
    Instant currentTime();

    /** True only while this exact owner/generation still owns an unexpired lease according to the lease store. */
    boolean isCurrent(String leaseName, String ownerId, long fencingToken);

    boolean release(String leaseName, String ownerId, long fencingToken);

    record LeaseSnapshot(
            String leaseName,
            String ownerId,
            long fencingToken,
            Instant acquiredAt,
            Instant heartbeatAt,
            Instant expiresAt) {
    }
}
