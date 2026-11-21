package com.securedoc.platform.domain.pipeline;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * The stored state of one export destination (an integration connection, or the local export folder). Codes, ids and
 * times only.
 *
 * @param consecutiveFailures kept for the task 0027 rows and shown in Support; no longer used to pause (task 0028
 *                            pauses by evidence: the destination's own check)
 * @param rateLimitedUntil    429: none of its jobs is taken before this time
 * @param workerReduction     429: workers taken off this destination (it always keeps at least 1)
 * @param lastRateLimitedAt   the last 429 (workers come back one at a time after quiet minutes)
 * @param lastCheckFailedAt   when its own check (the connection test) last failed
 */
public record ExportDestinationStatus(
        TenantId tenantId,
        IntegrationConnectionId connectionId,
        DestinationState state,
        Instant pausedAt,
        int consecutiveFailures,
        Instant lastProbeAt,
        String lastLvCode,
        Instant updatedAt,
        Instant rateLimitedUntil,
        int workerReduction,
        Instant lastRateLimitedAt,
        Instant lastCheckFailedAt) {

    public ExportDestinationStatus {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(state, "state");
        if (workerReduction < 0) throw new IllegalArgumentException("workerReduction must be >= 0");
    }

    /** The task 0027 shape: not slowed down, no failed check. */
    public ExportDestinationStatus(TenantId tenantId, IntegrationConnectionId connectionId, DestinationState state,
                                   Instant pausedAt, int consecutiveFailures, Instant lastProbeAt, String lastLvCode,
                                   Instant updatedAt) {
        this(tenantId, connectionId, state, pausedAt, consecutiveFailures, lastProbeAt, lastLvCode, updatedAt, null, 0,
                null, null);
    }

    public static ExportDestinationStatus working(TenantId tenantId, IntegrationConnectionId connectionId) {
        return new ExportDestinationStatus(tenantId, connectionId, DestinationState.WORKING, null, 0, null, null, null);
    }

    /** Workers this destination may use now: the limit less the 429 reduction, never below 1. */
    public int workerLimit(int perDestinationLimit) {
        return Math.max(1, perDestinationLimit - workerReduction);
    }
}
