package com.securedoc.platform.application.audit.query;

import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Filter criteria for querying audit records. TenantId is always required.
 * UUID and Instant fields are pre-validated — the infrastructure layer receives typed values only.
 * Optional action/outcome sets support investigation presets without weakening tenant scoping.
 */
public record AuditRecordFilter(
        TenantId tenantId,
        String action,
        String outcome,
        String resourceType,
        String resourceId,
        UUID actorId,
        UUID correlationId,
        Instant from,
        Instant to,
        int limit,
        boolean includeRoutineNoOpScans,
        Set<String> actions,
        Set<String> outcomes
) {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 5000;

    public AuditRecordFilter {
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        if (limit <= 0) limit = DEFAULT_LIMIT;
        if (limit > MAX_LIMIT) limit = MAX_LIMIT;
        actions = actions == null ? Set.of() : Set.copyOf(actions);
        outcomes = outcomes == null ? Set.of() : Set.copyOf(outcomes);
    }

    /** Backward-compatible constructor used by existing product queries. */
    public AuditRecordFilter(TenantId tenantId, String action, String outcome, String resourceType,
                             String resourceId, UUID actorId, UUID correlationId, Instant from,
                             Instant to, int limit, boolean includeRoutineNoOpScans) {
        this(tenantId, action, outcome, resourceType, resourceId, actorId, correlationId,
                from, to, limit, includeRoutineNoOpScans, Set.of(), Set.of());
    }

    public static AuditRecordFilter ofTenant(TenantId tenantId) {
        return new AuditRecordFilter(tenantId, null, null, null, null, null, null, null, null,
                DEFAULT_LIMIT, true, Set.of(), Set.of());
    }

    public Optional<String> actionOpt() { return Optional.ofNullable(action); }
    public Optional<String> outcomeOpt() { return Optional.ofNullable(outcome); }
    public Optional<String> resourceTypeOpt() { return Optional.ofNullable(resourceType); }
    public Optional<String> resourceIdOpt() { return Optional.ofNullable(resourceId); }
    public Optional<UUID> actorIdOpt() { return Optional.ofNullable(actorId); }
    public Optional<UUID> correlationIdOpt() { return Optional.ofNullable(correlationId); }
    public Optional<Instant> fromOpt() { return Optional.ofNullable(from); }
    public Optional<Instant> toOpt() { return Optional.ofNullable(to); }
}
