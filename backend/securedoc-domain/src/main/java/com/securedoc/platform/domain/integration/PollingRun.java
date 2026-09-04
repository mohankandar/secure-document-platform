package com.securedoc.platform.domain.integration;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Auditable polling execution. activeLockKey is non-null only while RUNNING. */
public record PollingRun(
        UUID id,
        TenantId tenantId,
        IntegrationConnectionId integrationConnectionId,
        PollingRunTrigger trigger,
        PollingRunStatus status,
        String activeLockKey,
        Instant startedAt,
        Instant completedAt,
        int packagesDiscovered,
        int documentsExpected,
        int documentsRetrieved,
        int packagesAcknowledged,
        String failureCategory,
        String failureDetail) {

    public PollingRun {
        Objects.requireNonNull(id);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(integrationConnectionId);
        Objects.requireNonNull(trigger);
        Objects.requireNonNull(status);
        Objects.requireNonNull(startedAt);
        if (packagesDiscovered < 0 || documentsExpected < 0 || documentsRetrieved < 0 || packagesAcknowledged < 0) {
            throw new IllegalArgumentException("Polling run counts cannot be negative");
        }
        if (status == PollingRunStatus.RUNNING && (activeLockKey == null || activeLockKey.isBlank())) {
            throw new IllegalArgumentException("Running polling run requires an active lock key");
        }
        if (status != PollingRunStatus.RUNNING && activeLockKey != null) {
            throw new IllegalArgumentException("Completed polling run cannot retain active lock key");
        }
        failureCategory = normalize(failureCategory, 120);
        failureDetail = normalize(failureDetail, 500);
    }

    public static PollingRun start(TenantId tenantId, IntegrationConnectionId connectionId,
                                   PollingRunTrigger trigger, Instant now) {
        return new PollingRun(UUID.randomUUID(), tenantId, connectionId, trigger, PollingRunStatus.RUNNING,
                connectionId.toString(), now, null, 0, 0, 0, 0, null, null);
    }

    public PollingRun complete(PollingRunStatus finalStatus, int discovered, int expected, int retrieved,
                               int acknowledged, String failureCategory, String failureDetail, Instant now) {
        if (finalStatus == PollingRunStatus.RUNNING) throw new IllegalArgumentException("Final polling status is required");
        return new PollingRun(id, tenantId, integrationConnectionId, trigger, finalStatus, null, startedAt, now,
                discovered, expected, retrieved, acknowledged, failureCategory, failureDetail);
    }

    private static String normalize(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}
