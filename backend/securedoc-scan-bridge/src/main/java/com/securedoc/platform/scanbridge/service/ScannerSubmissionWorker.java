package com.securedoc.platform.scanbridge.service;

import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.model.ScanSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable asynchronous delivery worker. Operator Submit ends after QUEUED is persisted; this worker
 * performs idempotent create/upload/reconciliation without blocking the next physical package.
 */
@Service
public class ScannerSubmissionWorker {
    private static final Logger log = LoggerFactory.getLogger(ScannerSubmissionWorker.class);
    /** Automatic retries after the first attempt, counted since the item was last queued by a person. */
    static final int MAX_AUTOMATIC_RETRIES = 3;
    static final String ACCEPTANCE_PENDING = "LEXAVAULT_ACCEPTANCE_PENDING";
    /** Task 0024: shown to the person as the reference when LexaVault did not accept within the wait limit. */
    static final String ACCEPTANCE_WAIT_EXPIRED = "LEXAVAULT_ACCEPTANCE_WAIT_EXPIRED";
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
        try {
            sessions.purgeExpiredTerminalSessions(clock.instant());
        } catch (RuntimeException ex) {
            // Housekeeping must never stop delivery of the durable queue.
            log.warn("SCAN_BRIDGE_TERMINAL_PURGE_FAILED exceptionType={}", ex.getClass().getSimpleName());
        }
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
                waitForAcceptance(current);
            }
        } catch (LexaVaultScannerDeliveryClient.DeliveryException ex) {
            if (ex.retryable()) scheduleRetry(sessions.get(sessionId), ex.safeCode(), ex.retryAfter());
            else sessions.markAttentionRequired(sessionId, ex.safeCode());
        } catch (RuntimeException ex) {
            // Unknown local/runtime errors are not blindly retried forever because they may indicate corrupt state.
            sessions.markAttentionRequired(sessionId, "SCANNER_DELIVERY_INTERNAL_ERROR");
            log.warn("SCAN_BRIDGE_DELIVERY_INTERNAL_ERROR sessionId={} exceptionType={}", sessionId, ex.getClass().getSimpleName());
        }
    }

    private void scheduleRetry(ScanSession current, String reasonCode, Duration retryAfter) {
        // Task 0023 D2 (#82): retries are capped. After the first attempt and MAX_AUTOMATIC_RETRIES spaced
        // retries the item goes to ATTENTION_REQUIRED with its last code, where the scanner screen and the
        // delivery-items list show it to a person (manual retry: POST /delivery-items/{id}/retry).
        if (current.deliveryAttemptCount() > MAX_AUTOMATIC_RETRIES) {
            log.warn("SCAN_BRIDGE_DELIVERY_RETRIES_EXHAUSTED sessionId={} submissionId={} reasonCode={} attempts={}",
                    current.sessionId(), current.submissionId(), reasonCode, current.deliveryAttemptCount());
            sessions.markAttentionRequired(current.sessionId(), reasonCode);
            return;
        }
        // Task 0024: a 429 waits as long as LexaVault's Retry-After asks, up to retry-after-max-seconds.
        // Without a usable Retry-After (missing, unreadable, zero or in the past) the normal spacing applies.
        long delay;
        if (retryAfter != null && !retryAfter.isZero() && !retryAfter.isNegative()) {
            long maxSeconds = Math.max(1, properties.getDelivery().getRetryAfterMaxSeconds());
            delay = retryAfter.compareTo(Duration.ofSeconds(maxSeconds)) > 0
                    ? maxSeconds * 1000L
                    : retryAfter.toMillis();
        } else {
            delay = backoffMs(current.deliveryAttemptCount() - 1);
        }
        Instant next = clock.instant().plusMillis(delay);
        sessions.scheduleRetry(current.sessionId(), reasonCode, next);
        log.warn("SCAN_BRIDGE_DELIVERY_RETRY_SCHEDULED sessionId={} submissionId={} reasonCode={} attempt={} retryDelayMs={}",
                current.sessionId(), current.submissionId(), reasonCode, current.deliveryAttemptCount(), delay);
    }

    /**
     * Task 0024: LexaVault has the document but has not accepted it. That is waiting, not a failure: it does
     * not count toward MAX_AUTOMATIC_RETRIES. The item is re-checked with the normal spacing (initial delay,
     * growing with the time already waited, up to the maximum delay) until acceptance-wait-limit-seconds since
     * the wait began; then it needs a person.
     */
    private void waitForAcceptance(ScanSession current) {
        Instant now = clock.instant();
        Instant since = current.acceptancePendingSince() == null ? now : current.acceptancePendingSince();
        Duration limit = Duration.ofSeconds(Math.max(1, properties.getDelivery().getAcceptanceWaitLimitSeconds()));
        Instant deadline = since.plus(limit);
        if (!now.isBefore(deadline)) {
            log.warn("SCAN_BRIDGE_ACCEPTANCE_WAIT_EXPIRED sessionId={} submissionId={} packageId={} waitedSeconds={} limitSeconds={}",
                    current.sessionId(), current.submissionId(), current.lexavaultPackageId(),
                    Duration.between(since, now).toSeconds(), limit.toSeconds());
            sessions.markAttentionRequired(current.sessionId(), ACCEPTANCE_WAIT_EXPIRED);
            return;
        }
        long waitedMs = Duration.between(since, now).toMillis();
        long initial = Math.max(250, properties.getDelivery().getRetryInitialDelayMs());
        long max = Math.max(initial, properties.getDelivery().getRetryMaxDelayMs());
        long delay = Math.min(max, Math.max(initial, waitedMs));
        Instant next = now.plusMillis(delay);
        if (next.isAfter(deadline)) next = deadline;
        sessions.waitForAcceptance(current.sessionId(), ACCEPTANCE_PENDING, next);
        log.info("SCAN_BRIDGE_ACCEPTANCE_PENDING sessionId={} submissionId={} packageId={} waitedSeconds={} nextCheckDelayMs={}",
                current.sessionId(), current.submissionId(), current.lexavaultPackageId(),
                Duration.between(since, now).toSeconds(), Duration.between(now, next).toMillis());
    }

    private long backoffMs(int exponentRaw) {
        int exponent = Math.min(20, Math.max(0, exponentRaw));
        long initial = Math.max(250, properties.getDelivery().getRetryInitialDelayMs());
        long max = Math.max(initial, properties.getDelivery().getRetryMaxDelayMs());
        long delay;
        try { delay = Math.multiplyExact(initial, 1L << exponent); }
        catch (ArithmeticException ex) { delay = max; }
        return Math.min(max, delay);
    }

    private boolean accepted(String intakeStatus) {
        return LexaVaultScannerDeliveryClient.ACCEPTED_FOR_PROCESSING.equals(intakeStatus);
    }
}
