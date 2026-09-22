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

    /**
     * Acquires one semantic package-processing attempt. Recovery dispatch increments the durable
     * attempt counter. Ordinary non-recovery continuations preserve the current attempt budget; an
     * explicit fresh redrive can request a reset through the bounded overload. The fencing token
     * remains monotonic and is intentionally independent from the retry budget.
     *
     * <p>The default preserves compatibility for non-database test adapters; production adapters
     * should override this so attempt counting is durable and processing-cycle aware.
     */
    default Optional<LeaseSnapshot> acquireProcessingAttempt(PackageId packageId,
                                                             TenantId tenantId,
                                                             String ownerId,
                                                             Duration leaseDuration,
                                                             boolean recovery) {
        return acquireOrRenew(packageId, tenantId, ownerId, leaseDuration);
    }

    /**
     * Bounded variant used by the production dispatcher. Recovery must never create an attempt
     * above the configured package-processing budget. Implementations with durable coordination
     * state should enforce the ceiling atomically in the lease store.
     */
    default Optional<LeaseSnapshot> acquireProcessingAttempt(PackageId packageId,
                                                             TenantId tenantId,
                                                             String ownerId,
                                                             Duration leaseDuration,
                                                             boolean recovery,
                                                             int maxAttempts) {
        return acquireProcessingAttempt(packageId, tenantId, ownerId, leaseDuration, recovery)
                .filter(lease -> !recovery || lease.attemptCount() <= Math.max(1, maxAttempts));
    }


    /**
     * Processing-cycle-aware bounded acquisition. A fresh human-requested redrive may reset the
     * automatic recovery budget to attempt 1, while ordinary continuations (for example after
     * manual review or final approval) must preserve the current durable attempt count.
     */
    default Optional<LeaseSnapshot> acquireProcessingAttempt(PackageId packageId,
                                                             TenantId tenantId,
                                                             String ownerId,
                                                             Duration leaseDuration,
                                                             boolean recovery,
                                                             boolean resetAttemptBudget,
                                                             int maxAttempts) {
        return acquireProcessingAttempt(packageId, tenantId, ownerId, leaseDuration, recovery, maxAttempts);
    }

    /**
     * Atomically fences an expired terminal attempt before the package is moved to Technical
     * Attention. This prevents a late heartbeat or a second HA node from reviving the same
     * exhausted attempt while the terminal transition is being recorded.
     */
    default Optional<LeaseSnapshot> claimExpiredForExhaustion(PackageId packageId,
                                                               TenantId tenantId,
                                                               long expectedFencingToken,
                                                               int expectedAttemptCount,
                                                               String ownerId,
                                                               Duration leaseDuration) {
        return Optional.empty();
    }

    Optional<LeaseSnapshot> current(PackageId packageId);

    /** Returns expired leases ordered oldest-first according to the shared lease-store clock. */
    List<LeaseSnapshot> findExpired(int limit);

    /**
     * Finds PROCESSING packages that have no coordination lease at all and have been unchanged
     * for at least {@code staleAge}. This exists primarily to recover pre-HA/legacy work and
     * crash windows that occurred before a lease row could be created.
     */
    List<OrphanedProcessingSnapshot> findProcessingWithoutLease(Duration staleAge, int limit);

    Instant currentTime();

    boolean isCurrent(PackageId packageId, String ownerId, long fencingToken);

    boolean release(PackageId packageId, String ownerId, long fencingToken);

    record LeaseSnapshot(
            PackageId packageId,
            TenantId tenantId,
            String ownerId,
            long fencingToken,
            int attemptCount,
            Instant acquiredAt,
            Instant heartbeatAt,
            Instant expiresAt) {
        /** Backward-compatible constructor for focused test adapters created before attempt counting. */
        public LeaseSnapshot(PackageId packageId, TenantId tenantId, String ownerId, long fencingToken,
                             Instant acquiredAt, Instant heartbeatAt, Instant expiresAt) {
            this(packageId, tenantId, ownerId, fencingToken,
                    (int) Math.max(1L, Math.min(Integer.MAX_VALUE, fencingToken)),
                    acquiredAt, heartbeatAt, expiresAt);
        }
    }

    record OrphanedProcessingSnapshot(
            PackageId packageId,
            TenantId tenantId,
            Instant packageUpdatedAt) {
    }
}
