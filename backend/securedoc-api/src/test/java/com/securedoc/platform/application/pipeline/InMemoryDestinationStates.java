package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.application.port.out.ExportDestinationStateRepository;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.DestinationState;
import com.securedoc.platform.domain.pipeline.ExportDestinationStatus;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Destination states in memory, following the rules of {@code JdbcExportDestinationStateRepository}. */
final class InMemoryDestinationStates implements ExportDestinationStateRepository {
    private final Map<UUID, ExportDestinationStatus> rows = new LinkedHashMap<>();
    private final TestClock clock;

    InMemoryDestinationStates(TestClock clock) {
        this.clock = clock;
    }

    synchronized DestinationState stateOf(IntegrationConnectionId id) {
        ExportDestinationStatus s = rows.get(id.value());
        return s == null ? DestinationState.WORKING : s.state();
    }

    @Override
    public synchronized Optional<ExportDestinationStatus> find(TenantId tenantId, IntegrationConnectionId connectionId) {
        return Optional.ofNullable(rows.get(connectionId.value())).filter(s -> s.tenantId().equals(tenantId));
    }

    @Override
    public synchronized List<ExportDestinationStatus> findByTenantId(TenantId tenantId) {
        return rows.values().stream().filter(s -> s.tenantId().equals(tenantId)).toList();
    }

    @Override
    public synchronized TransientFailure recordTransientFailure(TenantId tenantId, IntegrationConnectionId connectionId,
                                                                int pauseThreshold, String lvCode) {
        ExportDestinationStatus s = row(tenantId, connectionId);
        int failures = s.consecutiveFailures() + 1;
        boolean pause = s.state() == DestinationState.WORKING && failures >= pauseThreshold;
        DestinationState state = pause ? DestinationState.PAUSED : s.state();
        rows.put(connectionId.value(), new ExportDestinationStatus(tenantId, connectionId, state,
                pause ? clock.instant() : s.pausedAt(), failures, pause ? clock.instant() : s.lastProbeAt(), lvCode,
                clock.instant()));
        return new TransientFailure(failures, pause, state);
    }

    @Override
    public synchronized void resetFailures(TenantId tenantId, IntegrationConnectionId connectionId) {
        ExportDestinationStatus s = rows.get(connectionId.value());
        if (s == null) return;
        rows.put(connectionId.value(), new ExportDestinationStatus(tenantId, connectionId, s.state(), s.pausedAt(), 0,
                s.lastProbeAt(), s.lastLvCode(), clock.instant()));
    }

    @Override
    public synchronized boolean transition(TenantId tenantId, IntegrationConnectionId connectionId,
                                           Set<DestinationState> from, DestinationState to, String lvCode) {
        ExportDestinationStatus s = row(tenantId, connectionId);
        if (!from.contains(s.state())) return false;
        rows.put(connectionId.value(), new ExportDestinationStatus(tenantId, connectionId, to,
                to == DestinationState.WORKING ? null : clock.instant(),
                to == DestinationState.WORKING ? 0 : s.consecutiveFailures(),
                to == DestinationState.PAUSED ? clock.instant() : s.lastProbeAt(),
                lvCode == null ? s.lastLvCode() : lvCode, clock.instant()));
        return true;
    }

    @Override
    public synchronized List<ExportDestinationStatus> findPausedDueForProbe(Duration probeInterval, int limit) {
        return rows.values().stream().filter(s -> s.state() == DestinationState.PAUSED)
                .filter(s -> s.lastProbeAt() == null || !s.lastProbeAt().plus(probeInterval).isAfter(clock.instant()))
                .limit(limit).toList();
    }

    @Override
    public synchronized void recordProbe(TenantId tenantId, IntegrationConnectionId connectionId) {
        ExportDestinationStatus s = rows.get(connectionId.value());
        if (s == null) return;
        rows.put(connectionId.value(), new ExportDestinationStatus(tenantId, connectionId, s.state(), s.pausedAt(),
                s.consecutiveFailures(), clock.instant(), s.lastLvCode(), clock.instant()));
    }

    private ExportDestinationStatus row(TenantId tenantId, IntegrationConnectionId connectionId) {
        return rows.computeIfAbsent(connectionId.value(), id -> ExportDestinationStatus.working(tenantId, connectionId));
    }
}
