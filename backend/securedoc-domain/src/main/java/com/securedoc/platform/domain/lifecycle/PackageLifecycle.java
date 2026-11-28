package com.securedoc.platform.domain.lifecycle;

import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/** Current lifecycle/retention state for one Intake Batch (internally DocumentPackage). */
public final class PackageLifecycle {
    private final PackageId packageId;
    private final TenantId tenantId;
    private int successfulExportCycles;
    private Instant lastExportedAt;
    private Instant originalDeleteAt;
    private Instant redactedDeleteAt;
    private Instant technicalHoldUntil;
    private Instant redactedPurgedAt;
    private Instant originalPurgedAt;
    private Instant purgedAt;
    private boolean finalApprovalGranted;
    private Instant updatedAt;

    private PackageLifecycle(PackageId packageId, TenantId tenantId, Instant now) {
        this.packageId = Objects.requireNonNull(packageId);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.updatedAt = Objects.requireNonNull(now);
    }

    public static PackageLifecycle create(PackageId packageId, TenantId tenantId) {
        return new PackageLifecycle(packageId, tenantId, Instant.now());
    }

    public static PackageLifecycle reconstitute(PackageId packageId, TenantId tenantId,
                                                int successfulExportCycles, Instant lastExportedAt,
                                                Instant originalDeleteAt, Instant redactedDeleteAt,
                                                Instant technicalHoldUntil, Instant redactedPurgedAt,
                                                Instant originalPurgedAt, Instant purgedAt,
                                                boolean finalApprovalGranted, Instant updatedAt) {
        PackageLifecycle lifecycle = new PackageLifecycle(packageId, tenantId, updatedAt);
        lifecycle.successfulExportCycles = successfulExportCycles;
        lifecycle.lastExportedAt = lastExportedAt;
        lifecycle.originalDeleteAt = originalDeleteAt;
        lifecycle.redactedDeleteAt = redactedDeleteAt;
        lifecycle.technicalHoldUntil = technicalHoldUntil;
        lifecycle.redactedPurgedAt = redactedPurgedAt;
        lifecycle.originalPurgedAt = originalPurgedAt;
        lifecycle.purgedAt = purgedAt;
        lifecycle.finalApprovalGranted = finalApprovalGranted;
        return lifecycle;
    }

    public int nextExportCycle() {
        return nextExportCycle(0);
    }

    /**
     * Task 0104: the limit of successful export cycles counts per batch. {@code cyclesBeforeBatch} is the number of
     * cycles that delivered the case's earlier batches (0 for a case of one batch). Only the current batch's cycles count
     * against the limit, so a new batch is never refused, while re-exports of one batch stay limited.
     */
    public int nextExportCycle(int cyclesBeforeBatch) {
        if (purgedAt != null || originalPurgedAt != null) {
            throw new IllegalStateException("Original artifacts have already been purged");
        }
        if (cyclesOfBatch(cyclesBeforeBatch) >= TenantLifecyclePolicy.MAX_SUCCESSFUL_EXPORT_CYCLES) {
            throw new IllegalStateException("Maximum successful export cycles reached");
        }
        return successfulExportCycles + 1;
    }

    public void recordSuccessfulExport(int cycleNumber, Instant exportedAt, TenantLifecyclePolicy policy) {
        recordSuccessfulExport(cycleNumber, exportedAt, policy, 0);
    }

    /** Task 0104: as {@link #recordSuccessfulExport(int, Instant, TenantLifecyclePolicy)}, the limit counted per batch. */
    public void recordSuccessfulExport(int cycleNumber, Instant exportedAt, TenantLifecyclePolicy policy,
                                       int cyclesBeforeBatch) {
        Objects.requireNonNull(exportedAt);
        Objects.requireNonNull(policy);
        if (cycleNumber != successfulExportCycles + 1) {
            throw new IllegalStateException("Export cycle is not the next successful cycle");
        }
        if (cyclesOfBatch(cyclesBeforeBatch) + 1 > TenantLifecyclePolicy.MAX_SUCCESSFUL_EXPORT_CYCLES) {
            throw new IllegalStateException("Maximum successful export cycles reached");
        }
        successfulExportCycles = cycleNumber;
        lastExportedAt = exportedAt;
        originalDeleteAt = exportedAt.plus(policy.originalRetentionHours(), ChronoUnit.HOURS);
        redactedDeleteAt = exportedAt.plus(policy.redactedCacheHours(), ChronoUnit.HOURS);
        technicalHoldUntil = null;
        redactedPurgedAt = null;
        finalApprovalGranted = false;
        updatedAt = exportedAt;
    }

    /** Starts the safety hold only for packages that never completed a successful export. */
    public boolean ensureNeverExportedTechnicalHold(Instant now, TenantLifecyclePolicy policy) {
        Objects.requireNonNull(now);
        Objects.requireNonNull(policy);
        if (successfulExportCycles > 0 || purgedAt != null || technicalHoldUntil != null) {
            return false;
        }
        technicalHoldUntil = now.plus(policy.neverExportedHoldHours(), ChronoUnit.HOURS);
        updatedAt = now;
        return true;
    }

    public void extendTechnicalHold(Instant now, Instant requestedUntil) {
        Objects.requireNonNull(now);
        Objects.requireNonNull(requestedUntil);
        if (successfulExportCycles > 0) {
            throw new IllegalStateException("Technical hold extension applies only before the first successful export");
        }
        if (purgedAt != null) {
            throw new IllegalStateException("Package lifecycle has already been purged");
        }
        if (!requestedUntil.isAfter(now)) {
            throw new IllegalArgumentException("Technical hold must end in the future");
        }
        if (technicalHoldUntil != null && !requestedUntil.isAfter(technicalHoldUntil)) {
            throw new IllegalArgumentException("Extended technical hold must be later than the current hold");
        }
        technicalHoldUntil = requestedUntil;
        updatedAt = now;
    }

    /** Replaces an investigation extension with a fresh normal technical-attention retention window. */
    public void restartTechnicalHold(Instant now, TenantLifecyclePolicy policy) {
        Objects.requireNonNull(now);
        Objects.requireNonNull(policy);
        if (successfulExportCycles > 0) {
            throw new IllegalStateException("Technical hold applies only before the first successful export");
        }
        if (purgedAt != null) {
            throw new IllegalStateException("Package lifecycle has already been purged");
        }
        technicalHoldUntil = now.plus(policy.neverExportedHoldHours(), ChronoUnit.HOURS);
        updatedAt = now;
    }

    public void grantFinalApproval(Instant now) {
        if (purgedAt != null) throw new IllegalStateException("Package lifecycle has already been purged");
        finalApprovalGranted = true;
        updatedAt = now;
    }

    public void clearFinalApproval(Instant now) {
        finalApprovalGranted = false;
        updatedAt = now;
    }

    public boolean canReprocess(Instant now) {
        return successfulExportCycles > 0
                && successfulExportCycles < TenantLifecyclePolicy.MAX_SUCCESSFUL_EXPORT_CYCLES
                && originalPurgedAt == null
                && purgedAt == null
                && originalDeleteAt != null
                && now.isBefore(originalDeleteAt);
    }

    /**
     * Task 0104: a delivered case takes its next batch. The delete times set by the last delivery stop, so the new
     * batch's files are not purged while it is processing or in Review; its own delivery sets them again (the keep
     * period runs from the last delivery).
     *
     * <p>Owner 2026-11-28: a case whose files were already deleted still takes its next batch (one case per contract).
     * The purge marks are cleared, so the new batch's files get their own keep period and are deleted after its own
     * delivery; the earlier deletion stays in the audit trail and in the batch history.
     *
     * @return true when the files of the earlier batches had been deleted
     */
    public boolean startNextBatch(Instant now) {
        Objects.requireNonNull(now);
        boolean earlierFilesDeleted = purgedAt != null || originalPurgedAt != null;
        purgedAt = null;
        originalPurgedAt = null;
        redactedPurgedAt = null;
        originalDeleteAt = null;
        redactedDeleteAt = null;
        updatedAt = now;
        return earlierFilesDeleted;
    }

    public boolean isRedactedPurgeDue(Instant now) {
        return purgedAt == null && redactedPurgedAt == null && redactedDeleteAt != null
                && !now.isBefore(redactedDeleteAt);
    }

    public boolean isFinalPurgeDue(Instant now) {
        if (purgedAt != null || originalPurgedAt != null) return false;
        if (successfulExportCycles > 0) {
            return originalDeleteAt != null && !now.isBefore(originalDeleteAt);
        }
        return technicalHoldUntil != null && !now.isBefore(technicalHoldUntil);
    }

    public void markRedactedPurged(Instant now) {
        if (redactedPurgedAt == null) redactedPurgedAt = now;
        updatedAt = now;
    }

    public void markFinalPurged(Instant now) {
        if (redactedPurgedAt == null) redactedPurgedAt = now;
        originalPurgedAt = now;
        purgedAt = now;
        finalApprovalGranted = false;
        updatedAt = now;
    }

    public PackageId packageId() { return packageId; }
    public TenantId tenantId() { return tenantId; }
    public int successfulExportCycles() { return successfulExportCycles; }
    public Optional<Instant> lastExportedAt() { return Optional.ofNullable(lastExportedAt); }
    public Optional<Instant> originalDeleteAt() { return Optional.ofNullable(originalDeleteAt); }
    public Optional<Instant> redactedDeleteAt() { return Optional.ofNullable(redactedDeleteAt); }
    public Optional<Instant> technicalHoldUntil() { return Optional.ofNullable(technicalHoldUntil); }
    public Optional<Instant> redactedPurgedAt() { return Optional.ofNullable(redactedPurgedAt); }
    public Optional<Instant> originalPurgedAt() { return Optional.ofNullable(originalPurgedAt); }
    public Optional<Instant> purgedAt() { return Optional.ofNullable(purgedAt); }
    public boolean finalApprovalGranted() { return finalApprovalGranted; }
    public Instant updatedAt() { return updatedAt; }

    private int cyclesOfBatch(int cyclesBeforeBatch) {
        if (cyclesBeforeBatch < 0 || cyclesBeforeBatch > successfulExportCycles) {
            throw new IllegalStateException("Cycles before the batch must be between 0 and the case's successful cycles");
        }
        return successfulExportCycles - cyclesBeforeBatch;
    }
}
