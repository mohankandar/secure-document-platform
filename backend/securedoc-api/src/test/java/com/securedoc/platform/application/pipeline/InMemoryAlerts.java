package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.application.port.out.ExportReachabilityAlertRepository;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The "can't reach its destinations" alert in memory, following {@code JdbcExportReachabilityAlertRepository}. */
final class InMemoryAlerts implements ExportReachabilityAlertRepository {
    private final Map<TenantId, Alert> rows = new LinkedHashMap<>();
    private final TestClock clock;

    InMemoryAlerts(TestClock clock) {
        this.clock = clock;
    }

    synchronized boolean raised(TenantId tenantId) {
        return find(tenantId).map(Alert::raised).orElse(false);
    }

    @Override
    public synchronized Optional<Alert> find(TenantId tenantId) {
        return Optional.ofNullable(rows.get(tenantId));
    }

    @Override
    public synchronized boolean raise(TenantId tenantId) {
        Alert a = rows.get(tenantId);
        if (a != null && a.raised()) return false;
        rows.put(tenantId, new Alert(tenantId, true, clock.instant(), clock.instant(), null));
        return true;
    }

    @Override
    public synchronized boolean clear(TenantId tenantId) {
        Alert a = rows.get(tenantId);
        if (a == null || !a.raised()) return false;
        rows.put(tenantId, new Alert(tenantId, false, a.raisedAt(), a.lastCheckAt(), clock.instant()));
        return true;
    }

    @Override
    public synchronized void recordCheck(TenantId tenantId) {
        Alert a = rows.get(tenantId);
        if (a != null) rows.put(tenantId, new Alert(tenantId, a.raised(), a.raisedAt(), clock.instant(), a.clearedAt()));
    }

    @Override
    public synchronized List<Alert> findRaisedDueForCheck(Duration interval, int limit) {
        return rows.values().stream().filter(Alert::raised)
                .filter(a -> a.lastCheckAt() == null || !a.lastCheckAt().plus(interval).isAfter(clock.instant()))
                .limit(limit).toList();
    }
}
