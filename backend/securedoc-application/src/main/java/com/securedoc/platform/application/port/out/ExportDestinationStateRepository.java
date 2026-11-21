package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.DestinationState;
import com.securedoc.platform.domain.pipeline.ExportDestinationStatus;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The state of each export destination (task 0027). A destination with no row is WORKING. */
public interface ExportDestinationStateRepository {

    Optional<ExportDestinationStatus> find(TenantId tenantId, IntegrationConnectionId connectionId);

    List<ExportDestinationStatus> findByTenantId(TenantId tenantId);

    /**
     * Adds one to the destination's failures in a row. When that reaches {@code pauseThreshold} while it is WORKING,
     * the destination becomes PAUSED (once: only one caller sees {@code pausedNow}).
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

    record TransientFailure(int consecutiveFailures, boolean pausedNow, DestinationState state) { }
}
