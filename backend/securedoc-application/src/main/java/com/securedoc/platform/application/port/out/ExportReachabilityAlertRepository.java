package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Task 0028: the "LexaVault can't reach its destinations" alert, one per tenant. Raised when every export destination
 * fails its check within the same minutes; cleared when any check passes. Database time.
 */
public interface ExportReachabilityAlertRepository {

    Optional<Alert> find(TenantId tenantId);

    /** Raises the alert. True only for the caller that raised it (it was not raised before). */
    boolean raise(TenantId tenantId);

    /** Clears a raised alert. True only for the caller that cleared it. */
    boolean clear(TenantId tenantId);

    void recordCheck(TenantId tenantId);

    /** Raised alerts not checked within {@code interval}. */
    List<Alert> findRaisedDueForCheck(Duration interval, int limit);

    record Alert(TenantId tenantId, boolean raised, Instant raisedAt, Instant lastCheckAt, Instant clearedAt) { }

    /** No alerts are kept (for tests and set-ups that need none). */
    static ExportReachabilityAlertRepository none() {
        return new ExportReachabilityAlertRepository() {
            @Override public Optional<Alert> find(TenantId tenantId) { return Optional.empty(); }
            @Override public boolean raise(TenantId tenantId) { return false; }
            @Override public boolean clear(TenantId tenantId) { return false; }
            @Override public void recordCheck(TenantId tenantId) { }
            @Override public List<Alert> findRaisedDueForCheck(Duration interval, int limit) { return List.of(); }
        };
    }
}
