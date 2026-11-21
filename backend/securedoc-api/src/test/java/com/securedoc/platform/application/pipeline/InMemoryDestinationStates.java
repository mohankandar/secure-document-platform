package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.application.port.out.ExportDestinationStateRepository;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.DestinationState;
import com.securedoc.platform.domain.pipeline.ExportDestinationStatus;

import java.time.Duration;
import java.time.Instant;
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

    synchronized ExportDestinationStatus rowOf(IntegrationConnectionId id) {
        return rows.get(id.value());
    }

    private static ExportDestinationStatus with(ExportDestinationStatus s, DestinationState state, Instant pausedAt,
                                                int failures, Instant lastProbeAt, String code, Instant updatedAt,
                                                Instant rateLimitedUntil, int reduction, Instant lastRateLimitedAt,
                                                Instant lastCheckFailedAt) {
        return new ExportDestinationStatus(s.tenantId(), s.connectionId(), state, pausedAt, failures, lastProbeAt, code,
                updatedAt, rateLimitedUntil, reduction, lastRateLimitedAt, lastCheckFailedAt);
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
        throw new AssertionError("task 0028 no longer pauses by counting failures in a row");
    }

    @Override
    public synchronized void resetFailures(TenantId tenantId, IntegrationConnectionId connectionId) {
        ExportDestinationStatus s = rows.get(connectionId.value());
        if (s == null) return;
        rows.put(connectionId.value(), with(s, s.state(), s.pausedAt(), 0, s.lastProbeAt(), s.lastLvCode(),
                clock.instant(), s.rateLimitedUntil(), s.workerReduction(), s.lastRateLimitedAt(),
                s.lastCheckFailedAt()));
    }

    @Override
    public synchronized boolean transition(TenantId tenantId, IntegrationConnectionId connectionId,
                                           Set<DestinationState> from, DestinationState to, String lvCode) {
        ExportDestinationStatus s = row(tenantId, connectionId);
        if (!from.contains(s.state())) return false;
        rows.put(connectionId.value(), with(s, to,
                to == DestinationState.WORKING ? null : clock.instant(),
                to == DestinationState.WORKING ? 0 : s.consecutiveFailures(),
                to == DestinationState.PAUSED ? clock.instant() : s.lastProbeAt(),
                lvCode == null ? s.lastLvCode() : lvCode, clock.instant(), s.rateLimitedUntil(), s.workerReduction(),
                s.lastRateLimitedAt(), s.lastCheckFailedAt()));
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
        rows.put(connectionId.value(), with(s, s.state(), s.pausedAt(), s.consecutiveFailures(), clock.instant(),
                s.lastLvCode(), clock.instant(), s.rateLimitedUntil(), s.workerReduction(), s.lastRateLimitedAt(),
                s.lastCheckFailedAt()));
    }

    @Override
    public synchronized void recordCheckFailed(TenantId tenantId, IntegrationConnectionId connectionId) {
        ExportDestinationStatus s = row(tenantId, connectionId);
        rows.put(connectionId.value(), with(s, s.state(), s.pausedAt(), s.consecutiveFailures(), s.lastProbeAt(),
                s.lastLvCode(), clock.instant(), s.rateLimitedUntil(), s.workerReduction(), s.lastRateLimitedAt(),
                clock.instant()));
    }

    @Override
    public synchronized void recordRateLimited(TenantId tenantId, IntegrationConnectionId connectionId, Duration wait,
                                               int maxReduction) {
        ExportDestinationStatus s = row(tenantId, connectionId);
        Instant until = clock.instant().plus(wait);
        if (s.rateLimitedUntil() != null && s.rateLimitedUntil().isAfter(until)) until = s.rateLimitedUntil();
        rows.put(connectionId.value(), with(s, s.state(), s.pausedAt(), s.consecutiveFailures(), s.lastProbeAt(),
                s.lastLvCode(), clock.instant(), until, Math.min(s.workerReduction() + 1, Math.max(0, maxReduction)),
                clock.instant(), s.lastCheckFailedAt()));
    }

    @Override
    public synchronized int restoreWorkers(Duration quietFor, int limit) {
        int restored = 0;
        Instant before = clock.instant().minus(quietFor);
        for (ExportDestinationStatus s : List.copyOf(rows.values())) {
            if (s.workerReduction() <= 0) continue;
            if (s.lastRateLimitedAt() != null && s.lastRateLimitedAt().isAfter(before)) continue;
            rows.put(s.connectionId().value(), with(s, s.state(), s.pausedAt(), s.consecutiveFailures(),
                    s.lastProbeAt(), s.lastLvCode(), clock.instant(), s.rateLimitedUntil(), s.workerReduction() - 1,
                    clock.instant(), s.lastCheckFailedAt()));
            if (++restored >= limit) break;
        }
        return restored;
    }

    private ExportDestinationStatus row(TenantId tenantId, IntegrationConnectionId connectionId) {
        return rows.computeIfAbsent(connectionId.value(), id -> ExportDestinationStatus.working(tenantId, connectionId));
    }
}
