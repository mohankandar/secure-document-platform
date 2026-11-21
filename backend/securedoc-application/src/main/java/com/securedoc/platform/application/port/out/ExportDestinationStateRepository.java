package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.DestinationState;
import com.securedoc.platform.domain.pipeline.ExportDestinationStatus;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The state of each export destination (task 0027; task 0028 added slowing down on 429 and the failed-check time). A
 * destination with no row is WORKING.
 */
public interface ExportDestinationStateRepository {

    Optional<ExportDestinationStatus> find(TenantId tenantId, IntegrationConnectionId connectionId);

    List<ExportDestinationStatus> findByTenantId(TenantId tenantId);

    /**
     * Task 0027: adds one to the destination's failures in a row and pauses it at {@code pauseThreshold}. Task 0028
     * no longer calls this (destinations are paused by evidence); kept for the stored counter.
     */
    TransientFailure recordTransientFailure(TenantId tenantId, IntegrationConnectionId connectionId, int pauseThreshold,
                                            String lvCode);

    /** The destination answered: its failures in a row go back to 0. Its state is unchanged. */
    void resetFailures(TenantId tenantId, IntegrationConnectionId connectionId);

    /**
     * Moves the destination to {@code to} if it is in one of {@code from} (no row counts as WORKING). Failures in a
     * row go back to 0 when the new state is WORKING.
     *
     * @return true when the state changed
     */
    boolean transition(TenantId tenantId, IntegrationConnectionId connectionId, Set<DestinationState> from,
                       DestinationState to, String lvCode);

    /** PAUSED destinations not probed within {@code probeInterval}. */
    List<ExportDestinationStatus> findPausedDueForProbe(Duration probeInterval, int limit);

    void recordProbe(TenantId tenantId, IntegrationConnectionId connectionId);

    /** The destination's own check failed now (the "all destinations at once" rule reads this time). */
    void recordCheckFailed(TenantId tenantId, IntegrationConnectionId connectionId);

    /**
     * A 429: none of the destination's jobs is taken for {@code wait}, and it gets one worker less, at most
     * {@code maxReduction} in all (so it always keeps one).
     */
    void recordRateLimited(TenantId tenantId, IntegrationConnectionId connectionId, Duration wait, int maxReduction);

    /**
     * Destinations slowed down by a 429 with no 429 for {@code quietFor} get one worker back (and the quiet time
     * starts again). Returns how many got one back.
     */
    int restoreWorkers(Duration quietFor, int limit);

    record TransientFailure(int consecutiveFailures, boolean pausedNow, DestinationState state) { }
}
