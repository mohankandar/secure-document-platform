package com.securedoc.platform.api.platform;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.api.dto.WatchedFolderScanResponse;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.intake.watched.WatchedFolderIntakeService;
import com.securedoc.platform.application.intake.watched.WatchedFolderScanLease;
import com.securedoc.platform.application.port.out.WatchedFolderLeasePort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.intake.WatchedFolderProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Multi-node-safe scheduler for watched-folder intake. */
@Component
public class WatchedFolderScheduler {
    private static final Logger log = LoggerFactory.getLogger(WatchedFolderScheduler.class);

    private final WatchedFolderIntakeService intakeService;
    private final WatchedFolderProperties watchedFolder;
    private final PlatformAdminProperties platformAdmin;
    private final AuditService auditService;
    private final WatchedFolderLeasePort leasePort;
    private final String processInstanceId = UUID.randomUUID().toString();
    private final AtomicReference<Snapshot> last = new AtomicReference<>();
    private final AtomicReference<WatchedFolderLeasePort.LeaseSnapshot> lastLease = new AtomicReference<>();
    private final AtomicReference<String> scannerRole = new AtomicReference<>("UNKNOWN");
    private final AtomicLong takeoverCount = new AtomicLong();
    private final AtomicReference<String> configurationState = new AtomicReference<>("UNKNOWN");

    public WatchedFolderScheduler(WatchedFolderIntakeService intakeService,
                                  WatchedFolderProperties watchedFolder,
                                  PlatformAdminProperties platformAdmin,
                                  AuditService auditService,
                                  WatchedFolderLeasePort leasePort) {
        this.intakeService = intakeService;
        this.watchedFolder = watchedFolder;
        this.platformAdmin = platformAdmin;
        this.auditService = auditService;
        this.leasePort = leasePort;
    }

    @Scheduled(fixedDelayString = "${securedoc.platform-admin.watched-folder-scan-interval-ms:60000}")
    public void scheduledScan() {
        if (!watchedFolder.isEnabled() || !platformAdmin.isWatchedFolderSchedulingEnabled()) return;
        var principal = scheduledPrincipal();
        if (principal == null) {
            scannerRole.set("NOT_CONFIGURED");
            last.set(new Snapshot(Instant.now(), "NOT_CONFIGURED", null));
            return;
        }
        try {
            scan(principal, CorrelationId.generate(), "SCHEDULED");
        } catch (RuntimeException alreadyRecorded) {
            // Task 0023 #57: scan(...) has audited and logged the failure (code and type). Not rethrown, so Spring's
            // scheduler does not print the exception and its cause, which can name filesystem paths.
        }
    }

    public WatchedFolderScanResponse scanManual(AuthenticatedPrincipal principal, CorrelationId correlationId) {
        TenantId configuredTenant = configuredWatchedFolderTenant();
        if (configuredTenant == null || !configuredTenant.equals(principal.tenantId())) {
            throw new AccessDeniedException(LvErrorCode.CFG_007, "Watched-folder intake is not configured for this tenant");
        }
        return scan(principal, correlationId, "MANUAL");
    }

    public WatchedFolderAdminStatus status() {
        WatchedFolderLeasePort.LeaseSnapshot lease = refreshLeaseBestEffort();
        Snapshot snapshot = last.get();
        return new WatchedFolderAdminStatus(
                watchedFolder.isEnabled(),
                platformAdmin.isWatchedFolderSchedulingEnabled(),
                Math.max(1000L, platformAdmin.getWatchedFolderScanIntervalMs()),
                watchedFolder.getRootPath(),
                watchedFolder.getTriggerFileName(),
                watchedFolder.getIncompleteVisibilityMinutes(),
                watchedFolder.getAcceptedBehavior(),
                watchedFolder.getRejectedBehavior(),
                watchedFolder.getProcessingDirectory(),
                platformAdmin.isWatchedFolderCoordinationEnabled(),
                logicalNodeId(),
                processInstanceId,
                scannerRole.get(),
                lease == null ? null : lease.ownerId(),
                lease == null ? null : lease.fencingToken(),
                lease == null ? null : lease.acquiredAt(),
                lease == null ? null : lease.heartbeatAt(),
                lease == null ? null : lease.expiresAt(),
                takeoverCount.get(),
                snapshot == null ? null : snapshot.at(),
                snapshot == null ? "NEVER_RUN" : snapshot.outcome(),
                snapshot == null ? null : snapshot.result());
    }

    @PreDestroy
    public void releaseLease() {
        if (!platformAdmin.isWatchedFolderCoordinationEnabled()) return;
        WatchedFolderLeasePort.LeaseSnapshot lease = lastLease.get();
        if (lease == null || !ownerId().equals(lease.ownerId())) return;
        try {
            leasePort.release(lease.leaseName(), lease.ownerId(), lease.fencingToken());
        } catch (RuntimeException ignored) {
            // Lease expiry is the crash-safe release mechanism. Graceful release is only an optimization.
        }
    }

    private WatchedFolderScanResponse scan(AuthenticatedPrincipal principal, CorrelationId correlationId, String mode) {
        Optional<WatchedFolderScanLease> scanLease = acquireScanLease(principal);
        if (scanLease.isEmpty()) {
            // Task 0023 #340: say why nothing was scanned (STANDBY, NOT_CONFIGURED, COORDINATION_DEGRADED) instead of
            // returning counts that look like a successful empty scan.
            WatchedFolderScanResponse response = emptyResponse(scannerRole.get());
            last.set(new Snapshot(Instant.now(), scannerRole.get(), response));
            if ("MANUAL".equals(mode)) {
                log.warn("WATCHED_FOLDER_SCAN_SKIPPED mode={} nodeId={} reasonCode={}", mode, logicalNodeId(), scannerRole.get());
            }
            return response;
        }

        try {
            var result = intakeService.scan(principal, correlationId, scanLease.get());
            var response = new WatchedFolderScanResponse(result.discovered(), result.waitingForTrigger(),
                    result.accepted(), result.rejected(), result.duplicates(), WatchedFolderScanResponse.SCANNED);
            last.set(new Snapshot(Instant.now(), "SUCCESS", response));
            boolean meaningfulActivity = result.discovered() > 0 || result.accepted() > 0
                    || result.rejected() > 0 || result.duplicates() > 0;
            boolean routineScheduledNoOp = "SCHEDULED".equals(mode) && !meaningfulActivity;
            if (!routineScheduledNoOp) {
                auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.WATCHED_FOLDER_SCAN_TRIGGERED,
                        "WatchedFolder", mode, correlationId,
                        "Watched-folder scan completed: mode=" + mode + ", discovered=" + result.discovered());
                log.info("WATCHED_FOLDER_SCAN_COMPLETED mode={} nodeId={} fencingToken={} discovered={} accepted={} rejected={} duplicates={}",
                        mode, logicalNodeId(), scanLease.get().fencingToken(), result.discovered(), result.accepted(), result.rejected(), result.duplicates());
            }
            return response;
        } catch (RuntimeException e) {
            last.set(new Snapshot(Instant.now(), "FAILED", null));
            String exceptionType = e.getClass().getSimpleName();
            auditService.recordFailure(principal.tenantId(), principal.actorId(), AuditAction.WATCHED_FOLDER_SCAN_TRIGGERED,
                    "WatchedFolder", mode, correlationId, "Watched-folder scan failed: " + com.securedoc.platform.application.error.LvErrorCodes.auditDetail(com.securedoc.platform.domain.error.LvErrorCode.INT_034, "SCAN_FAILED",
                            "mode=" + mode + ", exceptionType=" + exceptionType));
            // The type only: the message and the cause of a folder failure can name filesystem paths.
            log.warn("WATCHED_FOLDER_SCAN_FAILED mode={} nodeId={} fencingToken={} reasonCode=SCAN_FAILED exceptionType={}",
                    mode, logicalNodeId(), scanLease.get().fencingToken(), exceptionType);
            throw e;
        }
    }

    private Optional<WatchedFolderScanLease> acquireScanLease(AuthenticatedPrincipal principal) {
        if (!platformAdmin.isWatchedFolderCoordinationEnabled()) {
            scannerRole.set("ACTIVE_SINGLE_NODE");
            return Optional.of(WatchedFolderScanLease.singleNode());
        }
        if (logicalNodeId() == null) {
            scannerRole.set("NOT_CONFIGURED");
            return Optional.empty();
        }

        String leaseName = leaseName();
        try {
            Optional<WatchedFolderLeasePort.LeaseSnapshot> before = leasePort.current(leaseName);
            Optional<WatchedFolderLeasePort.LeaseSnapshot> acquired = leasePort.acquireOrRenew(
                    leaseName,
                    ownerId(),
                    Duration.ofMillis(Math.max(1000L, platformAdmin.getWatchedFolderLeaseDurationMs())));
            if (acquired.isEmpty()) {
                WatchedFolderLeasePort.LeaseSnapshot current = leasePort.current(leaseName).orElse(null);
                lastLease.set(current);
                scannerRole.set("STANDBY");
                return Optional.empty();
            }

            WatchedFolderLeasePort.LeaseSnapshot lease = acquired.get();
            lastLease.set(lease);
            scannerRole.set("ACTIVE");
            if (before.isPresent() && !ownerId().equals(before.get().ownerId())
                    && lease.fencingToken() > before.get().fencingToken()) {
                takeoverCount.incrementAndGet();
                log.info("WATCHED_FOLDER_LEASE_TAKEOVER nodeId={} fencingToken={} priorOwnerPresent=true",
                        logicalNodeId(), lease.fencingToken());
            }
            return Optional.of(new WatchedFolderScanLease(leaseName, ownerId(), lease.fencingToken()));
        } catch (RuntimeException unavailable) {
            // Fail closed. A node must never scan without shared coordination when coordination is enabled.
            scannerRole.set("COORDINATION_DEGRADED");
            last.set(new Snapshot(Instant.now(), "COORDINATION_DEGRADED", null));
            log.warn("WATCHED_FOLDER_COORDINATION_UNAVAILABLE nodeId={} reasonCode=LEASE_STORE_UNAVAILABLE", logicalNodeId());
            return Optional.empty();
        }
    }

    private WatchedFolderLeasePort.LeaseSnapshot refreshLeaseBestEffort() {
        if (!platformAdmin.isWatchedFolderCoordinationEnabled()) return null;
        String tenant = platformAdmin.getWatchedFolderTenantId();
        if (tenant == null || tenant.isBlank()) return lastLease.get();
        try {
            WatchedFolderLeasePort.LeaseSnapshot current = leasePort.current(leaseName()).orElse(null);
            lastLease.set(current);
            if (current != null && ownerId().equals(current.ownerId())) scannerRole.set("ACTIVE");
            else if (current != null && !"COORDINATION_DEGRADED".equals(scannerRole.get())) scannerRole.set("STANDBY");
            return current;
        } catch (RuntimeException invalidOrUnavailable) {
            return lastLease.get();
        }
    }

    private String leaseName() {
        String configured = platformAdmin.getWatchedFolderLeaseName();
        return configured == null || configured.isBlank() ? "watched-folder-intake" : configured.trim();
    }

    private TenantId configuredWatchedFolderTenant() {
        try {
            String configured = platformAdmin.getWatchedFolderTenantId();
            if (configured == null || configured.isBlank()) return null;
            return TenantId.of(UUID.fromString(configured));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private String logicalNodeId() {
        String configured = platformAdmin.getWatchedFolderNodeId();
        if (configured == null || configured.isBlank()) return null;
        return configured.trim();
    }

    private String ownerId() {
        String logical = logicalNodeId();
        return (logical == null ? "unconfigured" : logical) + "/" + processInstanceId;
    }

    private WatchedFolderScanResponse emptyResponse(String outcome) {
        return new WatchedFolderScanResponse(0, 0, 0, 0, 0, outcome);
    }

    private AuthenticatedPrincipal scheduledPrincipal() {
        String tenant = platformAdmin.getWatchedFolderTenantId();
        String actor = platformAdmin.getWatchedFolderActorId();
        if (tenant == null || tenant.isBlank() || actor == null || actor.isBlank()) {
            reportConfiguration(tenant == null || tenant.isBlank() ? "watched-folder-tenant-id" : "watched-folder-actor-id",
                    "WATCHED_FOLDER_NOT_CONFIGURED");
            return null;
        }
        TenantId tenantId;
        ActorId actorId;
        try {
            tenantId = TenantId.of(UUID.fromString(tenant.trim()));
        } catch (RuntimeException invalid) {
            reportConfiguration("watched-folder-tenant-id", "WATCHED_FOLDER_CONFIGURATION_INVALID");
            return null;
        }
        try {
            actorId = ActorId.of(UUID.fromString(actor.trim()));
        } catch (RuntimeException invalid) {
            reportConfiguration("watched-folder-actor-id", "WATCHED_FOLDER_CONFIGURATION_INVALID");
            return null;
        }
        reportConfiguration(null, null);
        return AuthenticatedPrincipal.of(actorId, tenantId,
                platformAdmin.getWatchedFolderActorUsername(), Set.of("PLATFORM_OPERATIONS_ADMIN"));
    }

    /**
     * Task 0023 #296/#340: a missing or invalid scheduled identity is logged (the setting's name and a code, never its
     * value), once per change rather than on every interval.
     */
    private void reportConfiguration(String setting, String reasonCode) {
        String state = setting == null ? "OK" : reasonCode + ":" + setting;
        String before = configurationState.getAndSet(state);
        if (setting != null && !state.equals(before)) {
            log.warn("{} setting=securedoc.platform-admin.{} reasonCode={}", reasonCode, setting, reasonCode);
        }
    }

    private record Snapshot(Instant at, String outcome, WatchedFolderScanResponse result) {}
}
