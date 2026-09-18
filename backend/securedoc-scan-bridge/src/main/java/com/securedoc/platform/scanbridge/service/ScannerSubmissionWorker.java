package com.securedoc.platform.scanbridge.service;

import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.model.ScanSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable asynchronous delivery worker. Operator Submit ends after QUEUED is persisted; this worker
 * performs idempotent create/upload/reconciliation without blocking the next physical package.
 */
@Service
public class ScannerSubmissionWorker {
    private static final Logger log = LoggerFactory.getLogger(ScannerSubmissionWorker.class);
    private final ScanBridgeProperties properties;
    private final ScanSessionService sessions;
    private final ScanArtifactService artifacts;
    private final LexaVaultScannerDeliveryClient client;
    private final Clock clock;

    @Autowired
    public ScannerSubmissionWorker(ScanBridgeProperties properties, ScanSessionService sessions,
                                   ScanArtifactService artifacts, LexaVaultScannerDeliveryClient client) {
        this(properties, sessions, artifacts, client, Clock.systemUTC());
    }

    ScannerSubmissionWorker(ScanBridgeProperties properties, ScanSessionService sessions,
                            ScanArtifactService artifacts, LexaVaultScannerDeliveryClient client, Clock clock) {
        this.properties = properties;
        this.sessions = sessions;
        this.artifacts = artifacts;
        this.client = client;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${securedoc.scan-bridge.delivery.poll-interval-ms:1000}")
    public void deliverDue() {
        if (!properties.isEnabled()) return;
        sessions.purgeExpiredTerminalSessions(clock.instant());
        for (ScanSession queued : sessions.dueForDelivery(clock.instant())) {
            try {
                deliverOne(queued.sessionId());
            } catch (Exception ex) {
                // Per-item failures are statefully handled below whenever possible. A defensive outer
                // boundary ensures one malformed local item cannot stop the remaining durable queue.
                log.warn("SCAN_BRIDGE_DELIVERY_WORKER_FAILED sessionId={} exceptionType={}",
                        queued.sessionId(), ex.getClass().getSimpleName());
            }
        }
    }

    void deliverOne(UUID sessionId) {
        ScanSession current = sessions.beginDelivery(sessionId);
        try {
            // If a prior request may have reached LexaVault, reconcile before attempting another upload.
            if (current.lexavaultPackageId() != null) {
                var status = client.status(current.lexavaultPackageId());
                if (accepted(status.intakeStatus())) {
                    sessions.markSubmitted(current.sessionId(), status.packageId());
                    return;
                }
                if ("INCOMPLETE_EXPIRED".equals(status.intakeStatus())) {
                    var replacement = client.createIntake(current, true);
                    current = sessions.replaceLexaVaultPackageForRetry(current.sessionId(), replacement.packageId());
                    if (accepted(replacement.intakeStatus())) {
                        sessions.markSubmitted(current.sessionId(), replacement.packageId());
                        return;
                    }
                }
            } else {
                var created = client.createIntake(current, false);
                current = sessions.recordLexaVaultPackage(current.sessionId(), created.packageId());
                if (accepted(created.intakeStatus())) {
                    sessions.markSubmitted(current.sessionId(), created.packageId());
                    return;
                }
                if ("INCOMPLETE_EXPIRED".equals(created.intakeStatus())) {
                    var replacement = client.createIntake(current, true);
                    current = sessions.replaceLexaVaultPackageForRetry(current.sessionId(), replacement.packageId());
                }
            }

            // A missing local PDF is terminal only after reconciliation proved LexaVault has not accepted it.
            if (!artifacts.exists(current.preparedScan())) {
                sessions.markAttentionRequired(current.sessionId(), "LOCAL_SCAN_ARTIFACT_MISSING");
                return;
            }
            var receipt = client.upload(current, artifacts.resolve(current.preparedScan()));
            if (!receipt.packageId().equals(current.lexavaultPackageId())) {
                sessions.markAttentionRequired(current.sessionId(), "LEXAVAULT_PACKAGE_RECONCILIATION_MISMATCH");
                return;
            }
            if (accepted(receipt.intakeStatus())) {
                sessions.markSubmitted(current.sessionId(), receipt.packageId());
                return;
            }

            var status = client.status(current.lexavaultPackageId());
            if (accepted(status.intakeStatus())) {
                sessions.markSubmitted(current.sessionId(), status.packageId());
            } else {
                scheduleRetry(current, "LEXAVAULT_ACCEPTANCE_PENDING");
            }
        } catch (LexaVaultScannerDeliveryClient.DeliveryException ex) {
            if (ex.retryable()) scheduleRetry(sessions.get(sessionId), ex.safeCode());
            else sessions.markAttentionRequired(sessionId, ex.safeCode());
        } catch (RuntimeException ex) {
            // Unknown local/runtime errors are not blindly retried forever because they may indicate corrupt state.
            sessions.markAttentionRequired(sessionId, "SCANNER_DELIVERY_INTERNAL_ERROR");
            log.warn("SCAN_BRIDGE_DELIVERY_INTERNAL_ERROR sessionId={} exceptionType={}", sessionId, ex.getClass().getSimpleName());
        }
    }

    private void scheduleRetry(ScanSession current, String reasonCode) {
        int exponent = Math.min(20, Math.max(0, current.deliveryAttemptCount() - 1));
        long initial = Math.max(250, properties.getDelivery().getRetryInitialDelayMs());
        long max = Math.max(initial, properties.getDelivery().getRetryMaxDelayMs());
        long factor = 1L << exponent;
        long delay;
        try { delay = Math.multiplyExact(initial, factor); }
        catch (ArithmeticException ex) { delay = max; }
        delay = Math.min(max, delay);
        Instant next = clock.instant().plusMillis(delay);
        sessions.scheduleRetry(current.sessionId(), reasonCode, next);
        log.warn("SCAN_BRIDGE_DELIVERY_RETRY_SCHEDULED sessionId={} submissionId={} reasonCode={} attempt={} retryDelayMs={}",
                current.sessionId(), current.submissionId(), reasonCode, current.deliveryAttemptCount(), delay);
    }

    private boolean accepted(String intakeStatus) {
        return LexaVaultScannerDeliveryClient.ACCEPTED_FOR_PROCESSING.equals(intakeStatus);
    }
}
