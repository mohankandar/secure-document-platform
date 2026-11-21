package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineJobStatus;
import com.securedoc.platform.domain.pipeline.PipelineStage;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The pipeline job queue (task 0027; task 0028 added the retry fields). Times (claims, next tries, failures) are the
 * store's own clock, so every server agrees.
 */
public interface PipelineJobRepository {

    /** Marks a job that waits because of its destination (paused), not because of its own failures. */
    String WAITING_FOR_DESTINATION = "DESTINATION_PAUSED";
    /** Marks a job that waits because no destination could be reached (LexaVault's own network). */
    String WAITING_FOR_NETWORK = "DESTINATIONS_UNREACHABLE";

    /**
     * Adds the job unless one already exists for the same tenant, stage, case, export cycle, destination and item.
     *
     * @return true when the job was added
     */
    boolean enqueue(PipelineJob job);

    Optional<PipelineJob> findByTenantIdAndId(TenantId tenantId, UUID id);

    List<PipelineJob> findByTenantIdAndPackageId(TenantId tenantId, PackageId packageId);

    /** The export jobs (EXPORT and SPLIT_EXPORT) of these cases, in one query (the Cases list). */
    List<PipelineJob> findExportJobsByPackageIds(TenantId tenantId, Collection<PackageId> packageIds);

    /** Oldest first. */
    List<PipelineJob> findByTenantIdAndStatus(TenantId tenantId, PipelineJobStatus status, int limit);

    /**
     * Claims the next due job of the stages: READY or WAITING_RETRY whose next try is due, whose destination is
     * WORKING (or has no state), is not waiting for a 429's Retry-After, and runs fewer jobs than its worker limit
     * ({@code perDestinationLimit} less its 429 reduction, never below 1). In priority order, then oldest. Jobs
     * another worker is taking are skipped ({@code FOR UPDATE SKIP LOCKED}). The claim gets a new token.
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
     * @return false when the claim is no longer current: nothing was saved
     */
    boolean finish(UUID id, long claimToken, Finish result);

    /** The task 0027 form: not a resend, not rebuilt now, not counted as a failure. */
    default boolean finish(UUID id, long claimToken, PipelineJobStatus status, int attempts, Duration retryDelay,
                           String lastLvCode, String lastDetail) {
        return finish(id, claimToken, new Finish(status, attempts, retryDelay, lastLvCode, lastDetail, false, false,
                false));
    }

    /**
     * RUNNING jobs whose claim ran out go back to READY, counting the lost run as an attempt and as a claim expiry; a
     * job whose claim has then run out {@code maxClaimExpiries} times goes to NEEDS_IT with {@code exhaustedLvCode}.
     * Returns the jobs as they are now.
     */
    List<PipelineJob> releaseExpiredClaims(int maxClaimExpiries, String exhaustedLvCode, int limit);

    /** Sets a job that is not running from one status to another, due now. False when it was not in {@code from}. */
    boolean changeStatus(TenantId tenantId, UUID id, PipelineJobStatus from, PipelineJobStatus to, int attempts,
                         String lastLvCode, String lastDetail);

    /**
     * The destination came back: its jobs that waited for it (WAITING_RETRY marked {@link #WAITING_FOR_DESTINATION}
     * or {@link #WAITING_FOR_NETWORK}) become READY and due now, so they go out in their original order. Jobs on their
     * own retry schedule keep it.
     */
    int makeDueNow(TenantId tenantId, IntegrationConnectionId destinationId);

    /** The network came back: the tenant's jobs marked {@link #WAITING_FOR_NETWORK} become READY and due now. */
    int makeNetworkWaitersDueNow(TenantId tenantId);

    /**
     * Resend all for one destination: its WAITING_FOR_YOU jobs become READY, due now, marked as a resend. Returns
     * their ids in their original order (oldest first).
     */
    List<UUID> resendAll(TenantId tenantId, IntegrationConnectionId destinationId);

    /**
     * The number of different cases whose export jobs to this destination failed since {@code within} ago (the
     * "answering but failing" rule).
     */
    int countRecentlyFailingCases(TenantId tenantId, IntegrationConnectionId destinationId, Duration within);

    /** NEEDS_IT jobs of this destination with this code, other than {@code exceptJobId} (the grouped IT alert). */
    int countNeedsIt(TenantId tenantId, IntegrationConnectionId destinationId, String lvCode, UUID exceptJobId);

    /** Task 0029: the tenant's export jobs still to be done (not DONE or STOPPED), counted per destination id. */
    Map<UUID, Integer> countOpenByDestination(TenantId tenantId);

    /**
     * A worker's result.
     *
     * @param retryDelay when the next try is due, from now; null for now
     * @param resend     the job stays marked as a resend (it waits for its destination or a 429, and is still one
     *                   attempt by a person)
     * @param rebuilt    the export was rebuilt in this run (once set, it stays set)
     * @param failed     this run failed: the first and last failure times are set
     */
    record Finish(PipelineJobStatus status, int attempts, Duration retryDelay, String lastLvCode, String lastDetail,
                  boolean resend, boolean rebuilt, boolean failed) { }
}
