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

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Task 0111 (owner's test, 2026-11-26): a case that still has no documents a set time after it was opened (7 days by
 * default) is closed as expired: cancelled, off the dashboard, kept with its audit entry for Reports. Only cases a person
 * or a folder opened; a machine intake (API) has its own expiry, which also cleans its files.
 */
public final class EmptyCaseExpiryService {

    private final PackageRepository packages;
    private final PackageDashboardVisibilityPort dashboard;
    private final AuditService audit;
    private final Duration after;

    public EmptyCaseExpiryService(PackageRepository packages, PackageDashboardVisibilityPort dashboard, AuditService audit,
                                  Duration after) {
        this.packages = Objects.requireNonNull(packages);
        this.dashboard = Objects.requireNonNull(dashboard);
        this.audit = Objects.requireNonNull(audit);
        if (after == null || after.isNegative() || after.isZero()) {
            throw new IllegalArgumentException("The expiry time for cases without documents must be positive");
        }
        this.after = after;
    }

    public Duration after() { return after; }

    /** Closes up to {@code limit} empty cases opened before {@code now - after}; returns how many were closed. */
    public int expireDue(Instant now, int limit, ActorId actor, CorrelationId correlationId) {
        Instant cutoff = now.minus(after);
        int closed = 0;
        for (DocumentPackage pkg : packages.findEmptyCasesCreatedBefore(cutoff, limit)) {
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
