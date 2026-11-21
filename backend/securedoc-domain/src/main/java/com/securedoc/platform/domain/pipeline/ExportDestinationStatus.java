package com.securedoc.platform.domain.pipeline;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;

/** The stored state of one export destination (an integration connection). Codes, ids and times only. */
public record ExportDestinationStatus(
        TenantId tenantId,
        IntegrationConnectionId connectionId,
        DestinationState state,
        Instant pausedAt,
        int consecutiveFailures,
        Instant lastProbeAt,
        String lastLvCode,
        Instant updatedAt) {

    public ExportDestinationStatus {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(state, "state");
    }

    public static ExportDestinationStatus working(TenantId tenantId, IntegrationConnectionId connectionId) {
        return new ExportDestinationStatus(tenantId, connectionId, DestinationState.WORKING, null, 0, null, null, null);
    }
}
