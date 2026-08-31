package com.securedoc.platform.api.platform;

import com.securedoc.platform.api.dto.WatchedFolderScanResponse;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.watched.WatchedFolderIntakeService;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.intake.WatchedFolderProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Unit 24 scheduler/operational state for the existing Unit 20 watched-folder intake semantics. */
@Component
public class WatchedFolderScheduler {
    private final WatchedFolderIntakeService intakeService;
    private final WatchedFolderProperties watchedFolder;
    private final PlatformAdminProperties platformAdmin;
    private final AuditService auditService;
    private final AtomicReference<Snapshot> last = new AtomicReference<>();

    public WatchedFolderScheduler(WatchedFolderIntakeService intakeService,
                                  WatchedFolderProperties watchedFolder,
                                  PlatformAdminProperties platformAdmin,
                                  AuditService auditService) {
        this.intakeService = intakeService;
        this.watchedFolder = watchedFolder;
        this.platformAdmin = platformAdmin;
        this.auditService = auditService;
    }

    @Scheduled(fixedDelayString = "${securedoc.platform-admin.watched-folder-scan-interval-ms:60000}")
    public void scheduledScan() {
        if (!watchedFolder.isEnabled() || !platformAdmin.isWatchedFolderSchedulingEnabled()) return;
        var principal = scheduledPrincipal();
        if (principal == null) {
            last.set(new Snapshot(Instant.now(), "NOT_CONFIGURED", null));
            return;
        }
        scan(principal, CorrelationId.generate(), "SCHEDULED");
    }

    public WatchedFolderScanResponse scanManual(AuthenticatedPrincipal principal, CorrelationId correlationId) {
        return scan(principal, correlationId, "MANUAL");
    }

    public WatchedFolderAdminStatus status() {
        Snapshot snapshot = last.get();
        return new WatchedFolderAdminStatus(
                watchedFolder.isEnabled(), platformAdmin.isWatchedFolderSchedulingEnabled(),
                Math.max(1000L, platformAdmin.getWatchedFolderScanIntervalMs()),
                watchedFolder.getRootPath(), watchedFolder.getTriggerFileName(),
                watchedFolder.getIncompleteVisibilityMinutes(), watchedFolder.getAcceptedBehavior(),
                watchedFolder.getRejectedBehavior(), snapshot == null ? null : snapshot.at(),
                snapshot == null ? "NEVER_RUN" : snapshot.outcome(),
                snapshot == null ? null : snapshot.result());
    }

    private WatchedFolderScanResponse scan(AuthenticatedPrincipal principal, CorrelationId correlationId, String mode) {
        try {
            var result = intakeService.scan(principal, correlationId);
            var response = new WatchedFolderScanResponse(result.discovered(), result.waitingForTrigger(),
                    result.accepted(), result.rejected(), result.duplicates());
            last.set(new Snapshot(Instant.now(), "SUCCESS", response));
            auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.WATCHED_FOLDER_SCAN_TRIGGERED,
                    "WatchedFolder", mode, correlationId,
                    "Watched-folder scan completed: mode=" + mode + ", discovered=" + result.discovered());
            return response;
        } catch (RuntimeException e) {
            last.set(new Snapshot(Instant.now(), "FAILED", null));
            auditService.recordFailure(principal.tenantId(), principal.actorId(), AuditAction.WATCHED_FOLDER_SCAN_TRIGGERED,
                    "WatchedFolder", mode, correlationId, "Watched-folder scan failed: mode=" + mode);
            throw e;
        }
    }

    private AuthenticatedPrincipal scheduledPrincipal() {
        try {
            if (platformAdmin.getWatchedFolderTenantId() == null || platformAdmin.getWatchedFolderTenantId().isBlank()
                    || platformAdmin.getWatchedFolderActorId() == null || platformAdmin.getWatchedFolderActorId().isBlank()) {
                return null;
            }
            return AuthenticatedPrincipal.of(
                    ActorId.of(UUID.fromString(platformAdmin.getWatchedFolderActorId())),
                    TenantId.of(UUID.fromString(platformAdmin.getWatchedFolderTenantId())),
                    platformAdmin.getWatchedFolderActorUsername(), Set.of("PLATFORM_ADMIN"));
        } catch (RuntimeException invalidConfiguration) {
            return null;
        }
    }

    private record Snapshot(Instant at, String outcome, WatchedFolderScanResponse result) {}
}
