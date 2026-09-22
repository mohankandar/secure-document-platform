package com.securedoc.platform.api.processing;

import com.securedoc.platform.application.port.out.PackageProcessingLeasePort;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.telemetry.ProductTelemetryService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns renewable package-processing leases for work executing in this JVM.
 * The owner identity combines the logical node with a unique process incarnation.
 */
@Component
public class DatabasePackageProcessingDispatchLease implements PackageProcessingDispatchLease {
    private static final Logger log = LoggerFactory.getLogger(DatabasePackageProcessingDispatchLease.class);

    private final PackageProcessingLeasePort leasePort;
    private final boolean enabled;
    private final String logicalNodeId;
    private final long leaseDurationMs;
    private final int maxProcessingAttempts;
    private final ProductTelemetryService productTelemetryService;
    private final String processInstanceId = UUID.randomUUID().toString();
    private final Map<UUID, Claim> activeClaims = new ConcurrentHashMap<>();

    public DatabasePackageProcessingDispatchLease(
            PackageProcessingLeasePort leasePort,
            ProductTelemetryService productTelemetryService,
            @Value("${securedoc.processing.coordination.enabled:true}") boolean enabled,
            @Value("${securedoc.logging.node-id:unknown}") String logicalNodeId,
            @Value("${securedoc.processing.coordination.lease-duration-ms:30000}") long leaseDurationMs,
            @Value("${securedoc.processing.coordination.max-processing-attempts:3}") int maxProcessingAttempts) {
        this.leasePort = leasePort;
        this.productTelemetryService = productTelemetryService;
        this.enabled = enabled;
        this.logicalNodeId = logicalNodeId == null || logicalNodeId.isBlank() ? "unknown" : logicalNodeId.trim();
        this.leaseDurationMs = Math.max(5000L, leaseDurationMs);
        this.maxProcessingAttempts = Math.max(2, maxProcessingAttempts);
    }

    @Override
    public synchronized Optional<Claim> tryClaim(TriggerPackageProcessingCommand command) {
        if (!enabled) {
            return Optional.of(Claim.singleNode(command.packageId(), command.tenantId()));
        }
        UUID key = command.packageId().value();
        if (activeClaims.containsKey(key)) {
            return Optional.empty();
        }

        String ownerId = ownerId();
        Optional<PackageProcessingLeasePort.LeaseSnapshot> acquired = leasePort.acquireProcessingAttempt(
                command.packageId(), command.tenantId(), ownerId, Duration.ofMillis(leaseDurationMs),
                command.recovery(), command.resetAttemptBudget(), maxProcessingAttempts);
        if (acquired.isEmpty()) {
            return Optional.empty();
        }
        var lease = acquired.get();
        Claim claim = new Claim(command.packageId(), command.tenantId(), ownerId,
                lease.fencingToken(), true);
        activeClaims.put(key, claim);
        try {
            productTelemetryService.processingAttempt(command.tenantId(), command.packageId(),
                    lease.attemptCount(), maxProcessingAttempts,
                    command.recovery(), command.recovery() && lease.fencingToken() == 1 && lease.attemptCount() == 2);
        } catch (RuntimeException ignored) {
            // Product telemetry must never affect processing ownership.
        }
        log.info("PACKAGE_PROCESSING_LEASE_ACQUIRED package={} tenant={} nodeId={} fencingToken={} attempt={} maxAttempts={} recovery={} resetAttemptBudget={}",
                command.packageId(), command.tenantId(), logicalNodeId, lease.fencingToken(), lease.attemptCount(),
                maxProcessingAttempts, command.recovery(), command.resetAttemptBudget());
        return Optional.of(claim);
    }

    @Scheduled(fixedDelayString = "${securedoc.processing.coordination.heartbeat-interval-ms:10000}")
    public void heartbeat() {
        if (!enabled || activeClaims.isEmpty()) return;
        for (Claim claim : new ArrayList<>(activeClaims.values())) {
            try {
                Optional<PackageProcessingLeasePort.LeaseSnapshot> renewed = leasePort.acquireOrRenew(
                        claim.packageId(), claim.tenantId(), claim.ownerId(), Duration.ofMillis(leaseDurationMs));
                if (renewed.isEmpty() || renewed.get().fencingToken() != claim.fencingToken()) {
                    activeClaims.remove(claim.packageId().value(), claim);
                    log.error("PACKAGE_PROCESSING_LEASE_LOST package={} tenant={} nodeId={} fencingToken={}",
                            claim.packageId(), claim.tenantId(), logicalNodeId, claim.fencingToken());
                }
            } catch (RuntimeException unavailable) {
                // Fail closed for ownership. The running task may encounter the same database outage;
                // recovery cannot take over until the shared lease store becomes available again.
                log.warn("PACKAGE_PROCESSING_LEASE_HEARTBEAT_FAILED package={} tenant={} nodeId={} reasonCode={}",
                        claim.packageId(), claim.tenantId(), logicalNodeId, unavailable.getClass().getSimpleName());
            }
        }
    }

    @Override
    public boolean isCurrent(Claim claim) {
        if (claim == null || !claim.coordinated()) return true;
        if (!activeClaims.containsKey(claim.packageId().value())) return false;
        try {
            return leasePort.isCurrent(claim.packageId(), claim.ownerId(), claim.fencingToken());
        } catch (RuntimeException unavailable) {
            // Never start queued work when authoritative ownership cannot be established.
            log.warn("PACKAGE_PROCESSING_LEASE_CURRENT_CHECK_FAILED package={} tenant={} nodeId={} reasonCode={}",
                    claim.packageId(), claim.tenantId(), logicalNodeId, unavailable.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public void release(Claim claim) {
        if (claim == null || !claim.coordinated()) return;
        activeClaims.remove(claim.packageId().value(), claim);
        try {
            leasePort.release(claim.packageId(), claim.ownerId(), claim.fencingToken());
        } catch (RuntimeException unavailable) {
            // Expiry is crash-safe release. Explicit release is an optimization.
            log.warn("PACKAGE_PROCESSING_LEASE_RELEASE_FAILED package={} tenant={} nodeId={} reasonCode={}",
                    claim.packageId(), claim.tenantId(), logicalNodeId, unavailable.getClass().getSimpleName());
        }
    }

    @PreDestroy
    public void releaseActiveClaims() {
        for (Claim claim : new ArrayList<>(activeClaims.values())) {
            release(claim);
        }
    }

    private String ownerId() {
        return logicalNodeId + "/" + processInstanceId;
    }
}
