package com.securedoc.platform.api.processing;

import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.application.port.out.PackageProcessingLeasePort;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Discovers orphaned asynchronous package work after a node/process disappears.
 *
 * <p>SUBMITTED work is safe to redispatch directly. PROCESSING work is dispatched with the
 * recovery flag so the application service rewinds only interrupted document states from the
 * retained original before continuing. Atomic lease acquisition in the dispatcher resolves races
 * when multiple healthy nodes discover the same orphan at once.
 */
@Component
public class PackageProcessingRecoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(PackageProcessingRecoveryScheduler.class);
    private static final ActorId SYSTEM_ACTOR = ActorId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000000"));

    private final PackageRepository packageRepository;
    private final PackageProcessingLeasePort leasePort;
    private final PackageProcessingDispatcher dispatcher;
    private final boolean enabled;
    private final int batchSize;
    private final String nodeId;

    public PackageProcessingRecoveryScheduler(
            PackageRepository packageRepository,
            PackageProcessingLeasePort leasePort,
            PackageProcessingDispatcher dispatcher,
            @Value("${securedoc.processing.coordination.enabled:true}") boolean enabled,
            @Value("${securedoc.processing.coordination.recovery-batch-size:100}") int batchSize,
            @Value("${securedoc.logging.node-id:unknown}") String nodeId) {
        this.packageRepository = packageRepository;
        this.leasePort = leasePort;
        this.dispatcher = dispatcher;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.nodeId = nodeId == null || nodeId.isBlank() ? "unknown" : nodeId.trim();
    }

    @Scheduled(fixedDelayString = "${securedoc.processing.coordination.recovery-scan-interval-ms:5000}")
    public void recoverOrphanedWork() {
        if (!enabled) return;

        final Instant databaseNow;
        try {
            databaseNow = leasePort.currentTime();
        } catch (RuntimeException unavailable) {
            log.warn("PACKAGE_PROCESSING_RECOVERY_SCAN_SKIPPED nodeId={} reasonCode=LEASE_STORE_UNAVAILABLE", nodeId);
            return;
        }

        recoverSubmitted(databaseNow);
        recoverExpiredProcessingLeases();
    }

    private void recoverSubmitted(Instant databaseNow) {
        PackageStatus status = PackageStatus.SUBMITTED;
        List<DocumentPackage> candidates;
        try {
            candidates = packageRepository.findByStatus(status, batchSize);
        } catch (RuntimeException unavailable) {
            log.warn("PACKAGE_PROCESSING_RECOVERY_SCAN_FAILED nodeId={} status={} reasonCode={}",
                    nodeId, status, unavailable.getClass().getSimpleName());
            return;
        }

        for (DocumentPackage pkg : candidates) {
            try {
                var current = leasePort.current(pkg.id());
                // A process can die after SUBMITTED commits but before the post-commit dispatcher
                // acquires its lease. Unclaimed SUBMITTED work is therefore safe to adopt.
                boolean orphaned = current.isEmpty() || !current.get().expiresAt().isAfter(databaseNow);
                if (!orphaned) continue;

                dispatcher.dispatch(TriggerPackageProcessingCommand.of(
                        pkg.id(), pkg.tenantId(), SYSTEM_ACTOR, CorrelationId.generate()));
                log.info("PACKAGE_PROCESSING_RECOVERY_DISPATCHED package={} tenant={} status={} nodeId={} recovery=false",
                        pkg.id(), pkg.tenantId(), status, nodeId);
            } catch (RuntimeException candidateFailure) {
                log.warn("PACKAGE_PROCESSING_RECOVERY_CANDIDATE_FAILED package={} tenant={} status={} nodeId={} reasonCode={}",
                        pkg.id(), pkg.tenantId(), status, nodeId, candidateFailure.getClass().getSimpleName());
            }
        }
    }

    private void recoverExpiredProcessingLeases() {
        try {
            for (var expired : leasePort.findExpired(batchSize)) {
                var pkg = packageRepository.findByIdAndTenantId(expired.packageId(), expired.tenantId()).orElse(null);
                if (pkg == null || pkg.status() != PackageStatus.PROCESSING) continue;

                dispatcher.dispatch(TriggerPackageProcessingCommand.recovery(
                        pkg.id(), pkg.tenantId(), SYSTEM_ACTOR, CorrelationId.generate()));
                log.info("PACKAGE_PROCESSING_RECOVERY_DISPATCHED package={} tenant={} status={} nodeId={} recovery=true priorFencingToken={}",
                        pkg.id(), pkg.tenantId(), pkg.status(), nodeId, expired.fencingToken());
            }
        } catch (RuntimeException unavailable) {
            log.warn("PACKAGE_PROCESSING_RECOVERY_EXPIRED_SCAN_FAILED nodeId={} reasonCode={}",
                    nodeId, unavailable.getClass().getSimpleName());
        }
    }
}
