package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineJobStatus;
import com.securedoc.platform.domain.pipeline.PipelineStage;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The pipeline job queue (task 0027). Times (claims, next tries) are the store's own clock, so every server agrees.
 */
public interface PipelineJobRepository {

    /**
     * Adds the job unless one already exists for the same tenant, stage, case, export cycle, destination and item.
     *
     * @return true when the job was added
     */
    boolean enqueue(PipelineJob job);

    Optional<PipelineJob> findByTenantIdAndId(TenantId tenantId, UUID id);

    List<PipelineJob> findByTenantIdAndPackageId(TenantId tenantId, PackageId packageId);

    /** Oldest first. */
    List<PipelineJob> findByTenantIdAndStatus(TenantId tenantId, PipelineJobStatus status, int limit);

    /**
     * Claims the next due job of the stages: READY or WAITING_RETRY whose next try is due, whose destination is
     * WORKING (or has no state) and has fewer than {@code perDestinationLimit} running jobs. In priority order, then
     * oldest. Jobs another worker is taking are skipped ({@code FOR UPDATE SKIP LOCKED}). The claim gets a new token.
     */
    Optional<PipelineJob> claimNext(Set<PipelineStage> stages, String owner, Duration lease, int perDestinationLimit);

    /** Claims one given job if it is in one of the statuses (a resend or an IT action). The claim gets a new token. */
    Optional<PipelineJob> claim(TenantId tenantId, UUID id, Set<PipelineJobStatus> from, String owner, Duration lease);

    /** Extends a claim. False when the claim is no longer current (another worker took the job). */
    boolean renew(UUID id, long claimToken, Duration lease);

    /** True while the job is RUNNING under this token and its claim has not run out. */
    boolean claimIsCurrent(UUID id, long claimToken);

    /**
     * Saves a worker's result, only if the job is still RUNNING under this token. The claim is cleared.
     *
     * @param retryDelay when the next try is due, from now; null for now
     * @return false when the claim is no longer current: nothing was saved
     */
    boolean finish(UUID id, long claimToken, PipelineJobStatus status, int attempts, Duration retryDelay,
                   String lastLvCode, String lastDetail);

    /**
     * RUNNING jobs whose claim ran out go back to READY, counting the lost run as an attempt; a job that has then used
     * {@code maxAttempts} goes to NEEDS_IT with {@code exhaustedLvCode}. Returns the jobs as they are now.
     */
    List<PipelineJob> releaseExpiredClaims(int maxAttempts, String exhaustedLvCode, int limit);

    /** Sets a job that is not running from one status to another, due now. False when it was not in {@code from}. */
    boolean changeStatus(TenantId tenantId, UUID id, PipelineJobStatus from, PipelineJobStatus to, int attempts,
                         String lastLvCode, String lastDetail);

    /** The destination's WAITING_RETRY jobs become READY and due now (a destination came back). */
    int makeDueNow(TenantId tenantId, IntegrationConnectionId destinationId);
}
