package com.securedoc.platform.application.lifecycle.service;

import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.port.out.PackageDashboardVisibilityPort;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Task 0111 (owner's test, 2026-11-26): a case that still has no documents a set time after it was opened (7 days by
 * default) is closed as expired: cancelled, off the dashboard, kept with its audit entry for Reports. Only cases a person
 * or a folder opened; a machine intake (API) has its own expiry, which also cleans its files.
 * Task 0108: the time is the entity's Business Admin setting (Cases page settings); the installation's value is the
 * default for an entity that has not set one.
 */
public final class EmptyCaseExpiryService {

    private final PackageRepository packages;
    private final PackageDashboardVisibilityPort dashboard;
    private final AuditService audit;
    private final Function<TenantId, Duration> afterFor;
    private final Supplier<Duration> shortest;

    /** One time for every entity. */
    public EmptyCaseExpiryService(PackageRepository packages, PackageDashboardVisibilityPort dashboard, AuditService audit,
                                  Duration after) {
        this(packages, dashboard, audit, tenant -> after, () -> after);
        checkPositive(after);
    }

    /**
     * @param afterFor each entity's time
     * @param shortest the shortest time of any entity: cases opened before now minus this are the candidates
     */
    public EmptyCaseExpiryService(PackageRepository packages, PackageDashboardVisibilityPort dashboard, AuditService audit,
                                  Function<TenantId, Duration> afterFor, Supplier<Duration> shortest) {
        this.packages = Objects.requireNonNull(packages);
        this.dashboard = Objects.requireNonNull(dashboard);
        this.audit = Objects.requireNonNull(audit);
        this.afterFor = Objects.requireNonNull(afterFor);
        this.shortest = Objects.requireNonNull(shortest);
    }

    private static void checkPositive(Duration after) {
        if (after == null || after.isNegative() || after.isZero()) {
            throw new IllegalArgumentException("The expiry time for cases without documents must be positive");
        }
    }

    /** The shortest time of any entity now. */
    public Duration after() { return shortest.get(); }

    /** Closes up to {@code limit} empty cases that are past their entity's time; returns how many were closed. */
    public int expireDue(Instant now, int limit, ActorId actor, CorrelationId correlationId) {
        Duration first = shortest.get();
        checkPositive(first);
        Map<TenantId, Duration> byTenant = new HashMap<>();
        int closed = 0;
        for (DocumentPackage pkg : packages.findEmptyCasesCreatedBefore(now.minus(first), limit)) {
            Duration after = byTenant.computeIfAbsent(pkg.tenantId(), afterFor);
            // Fail closed: an entity whose time cannot be read is left alone.
            if (after == null || after.isNegative() || after.isZero()) continue;
            Instant cutoff = now.minus(after);
            // Checked again here: the list is only a candidate list (a document may have arrived since).
            if (pkg.status() != PackageStatus.CREATED || pkg.documentCount() > 0
                    || pkg.packageType() == PackageType.API_SUBMISSION || !pkg.createdAt().isBefore(cutoff)) {
                continue;
            }
            pkg.cancel();
            packages.save(pkg);
            dashboard.hide(pkg.id(), pkg.tenantId(), actor, now);
            audit.recordSuccess(pkg.tenantId(), actor, AuditAction.PACKAGE_CANCELLED,
                    "DocumentPackage", pkg.id().toString(), correlationId,
                    "Case closed as expired: no documents " + after.toDays() + " days after it was opened");
            closed++;
        }
        return closed;
    }
}
