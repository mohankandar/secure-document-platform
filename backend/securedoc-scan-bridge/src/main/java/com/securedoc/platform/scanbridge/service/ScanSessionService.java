package com.securedoc.platform.scanbridge.service;

import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.model.PreparedScan;
import com.securedoc.platform.scanbridge.model.ScanSession;
import com.securedoc.platform.scanbridge.model.ScanSessionStatus;
import com.securedoc.platform.scanbridge.model.ScannerDeliveryItemResponse;
import com.securedoc.platform.scanbridge.model.StartScanSessionRequest;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Owns the durable local lifecycle of one physical package/one consolidated PDF. */
@Service
public class ScanSessionService {
    private static final Logger log = LoggerFactory.getLogger(ScanSessionService.class);

    private final ScanBridgeProperties properties;
    private final FileScanSessionStore store;
    private final ScanArtifactService artifacts;
    private final Clock clock;
    private final Map<UUID, ScanSession> sessions = new ConcurrentHashMap<>();
    /** The one operator-actionable package for this scanner: WAITING_FOR_SCAN or READY. */
    private final AtomicReference<UUID> actionableSessionId = new AtomicReference<>();

    @Autowired
    public ScanSessionService(ScanBridgeProperties properties, FileScanSessionStore store, ScanArtifactService artifacts) {
        this(properties, store, artifacts, Clock.systemUTC());
    }

    ScanSessionService(ScanBridgeProperties properties, FileScanSessionStore store, ScanArtifactService artifacts, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.artifacts = artifacts;
        this.clock = clock;
    }

    @PostConstruct
    public synchronized void recoverDurableState() {
        List<ScanSession> recovered = store.loadAll();
        UUID actionableId = null;
        for (ScanSession original : recovered) {
            ScanSession session = original;
            if (session.status() == ScanSessionStatus.SUBMITTING) {
                // A crash can happen after LexaVault accepted a request but before the bridge saw the response.
                // Returning to QUEUED is safe because the server contract is idempotent/reconcilable.
                session = copy(original, ScanSessionStatus.QUEUED, original.businessIdentifiers(), original.preparedScan(),
                        original.lexavaultPackageId(), original.deliveryAttemptCount(), original.queuedAt(), clock.instant(),
                        "RECOVERED_AFTER_RESTART", clock.instant(), original.submittedAt());
                store.save(session);
            }
            if (isOperatorActionable(session.status())) {
                if (actionableId != null) {
                    throw new IllegalStateException("Multiple durable operator-actionable scanner sessions exist for one Scanner Bridge");
                }
                actionableId = session.sessionId();
            }
            sessions.put(session.sessionId(), session);
        }
        actionableSessionId.set(actionableId);
        cleanupTerminalArtifacts();
        purgeExpiredTerminalSessions(clock.instant());
        if (!recovered.isEmpty()) {
            log.info("SCAN_BRIDGE_STATE_RECOVERED sessions={} actionablePresent={} queued={} attentionRequired={}",
                    sessions.size(), actionableSessionId.get() != null, count(ScanSessionStatus.QUEUED), count(ScanSessionStatus.ATTENTION_REQUIRED));
        }
    }

    public synchronized ScanSession start(StartScanSessionRequest request) {
        if (!properties.isEnabled()) {
            throw new ScanBridgeException("BRIDGE_DISABLED", "The local Scan Bridge is disabled.", HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (actionableSessionId.get() != null) {
            throw new ScanBridgeException("SCANNER_BUSY", "A scan package is already waiting for operator action on this scanner.", HttpStatus.CONFLICT);
        }
        Map<String, String> identifiers = sanitizeBusinessIdentifiers(request == null ? null : request.businessIdentifiers());
        Instant now = clock.instant();
        ScanSession session = new ScanSession(
                UUID.randomUUID(), UUID.randomUUID(), properties.getTenantId(), properties.getBridgeId(), properties.getSiteId(),
                properties.getScannerId(), properties.getMode(), ScanSessionStatus.WAITING_FOR_SCAN, identifiers,
                now, now, null, null, 0, null, null, null, null, null);
        store.save(session);
        sessions.put(session.sessionId(), session);
        actionableSessionId.set(session.sessionId());
        return session;
    }

    public Optional<ScanSession> activeWaitingSession() {
        UUID id = actionableSessionId.get();
        if (id == null) return Optional.empty();
        ScanSession session = sessions.get(id);
        if (session == null) {
            actionableSessionId.compareAndSet(id, null);
            return Optional.empty();
        }
        if (session.status() == ScanSessionStatus.WAITING_FOR_SCAN) return Optional.of(session);
        // READY is still operator-actionable and must continue blocking a new session, but it is not
        // eligible for another scanner acquisition.
        if (session.status() == ScanSessionStatus.READY) return Optional.empty();
        actionableSessionId.compareAndSet(id, null);
        return Optional.empty();
    }

    /** Restores a browser/tab to the one durable package that still needs operator action. */
    public Optional<ScanSession> currentActionableSession() {
        UUID id = actionableSessionId.get();
        if (id == null) return Optional.empty();
        ScanSession session = sessions.get(id);
        if (session == null || !isOperatorActionable(session.status())) {
            actionableSessionId.compareAndSet(id, null);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public ScanSession get(UUID sessionId) {
        ScanSession session = sessions.get(sessionId);
        if (session == null) {
            throw new ScanBridgeException("SCAN_SESSION_NOT_FOUND", "The scan session is no longer active.", HttpStatus.NOT_FOUND);
        }
        return session;
    }

    public synchronized ScanSession markReady(UUID sessionId, PreparedScan preparedScan) {
        ScanSession existing = get(sessionId);
        if (existing.status() != ScanSessionStatus.WAITING_FOR_SCAN) {
            throw new ScanBridgeException("SCAN_SESSION_NOT_WAITING", "Scan session is no longer waiting for a document.", HttpStatus.CONFLICT);
        }
        ScanSession ready = copy(existing, ScanSessionStatus.READY, existing.businessIdentifiers(), preparedScan,
                null, existing.deliveryAttemptCount(), null, null, null, null, null);
        store.save(ready);
        sessions.put(sessionId, ready);
        actionableSessionId.compareAndSet(null, sessionId);
        return ready;
    }

    /** Durable handoff point for the operator's Submit action. */
    public synchronized ScanSession queueForSubmission(UUID sessionId) {
        ScanSession existing = get(sessionId);
        if (existing.status() == ScanSessionStatus.QUEUED || existing.status() == ScanSessionStatus.SUBMITTING
                || existing.status() == ScanSessionStatus.SUBMITTED) {
            return existing;
        }
        if (existing.status() != ScanSessionStatus.READY || existing.preparedScan() == null) {
            throw new ScanBridgeException("SCAN_NOT_READY", "The scanned PDF is not ready for submission.", HttpStatus.CONFLICT);
        }
        if (!artifacts.exists(existing.preparedScan())) {
            throw new ScanBridgeException("SCAN_ARTIFACT_MISSING", "The prepared scan is no longer available locally.", HttpStatus.CONFLICT);
        }
        Instant now = clock.instant();
        ScanSession queued = copy(existing, ScanSessionStatus.QUEUED, existing.businessIdentifiers(), existing.preparedScan(),
                existing.lexavaultPackageId(), existing.deliveryAttemptCount(), now, now, null, null, null);
        store.save(queued);
        sessions.put(sessionId, queued);
        actionableSessionId.compareAndSet(sessionId, null);
        log.info("SCAN_BRIDGE_SUBMISSION_QUEUED bridgeId={} siteId={} scannerId={} sessionId={} submissionId={} sourceDocumentId={} sizeBytes={}",
                existing.bridgeId(), existing.siteId(), existing.scannerId(), existing.sessionId(), existing.submissionId(),
                existing.preparedScan().sourceDocumentId(), existing.preparedScan().sizeBytes());
        return queued;
    }

    public synchronized ScanSession beginDelivery(UUID sessionId) {
        ScanSession existing = get(sessionId);
        if (existing.status() != ScanSessionStatus.QUEUED) {
            throw new IllegalStateException("Only QUEUED scanner sessions may begin delivery");
        }
        ScanSession submitting = copy(existing, ScanSessionStatus.SUBMITTING, existing.businessIdentifiers(), existing.preparedScan(),
                existing.lexavaultPackageId(), existing.deliveryAttemptCount() + 1, existing.queuedAt(), null,
                null, null, existing.submittedAt());
        store.save(submitting);
        sessions.put(sessionId, submitting);
        return submitting;
    }

    public synchronized ScanSession recordLexaVaultPackage(UUID sessionId, UUID packageId) {
        ScanSession existing = get(sessionId);
        if (existing.status() != ScanSessionStatus.SUBMITTING && existing.status() != ScanSessionStatus.QUEUED) {
            throw new IllegalStateException("LexaVault package identity may only be recorded during scanner delivery");
        }
        if (existing.lexavaultPackageId() != null && !existing.lexavaultPackageId().equals(packageId)) {
            throw new IllegalStateException("Scanner submission reconciled to a different LexaVault package id");
        }
        ScanSession updated = copy(existing, existing.status(), existing.businessIdentifiers(), existing.preparedScan(), packageId,
                existing.deliveryAttemptCount(), existing.queuedAt(), existing.nextDeliveryAttemptAt(),
                existing.lastDeliveryErrorCode(), existing.lastDeliveryErrorAt(), existing.submittedAt());
        store.save(updated);
        sessions.put(sessionId, updated);
        return updated;
    }

    public synchronized ScanSession replaceLexaVaultPackageForRetry(UUID sessionId, UUID packageId) {
        ScanSession existing = get(sessionId);
        if (existing.status() != ScanSessionStatus.SUBMITTING) {
            throw new IllegalStateException("Scanner retry package identity may only change during delivery");
        }
        ScanSession updated = copy(existing, existing.status(), existing.businessIdentifiers(), existing.preparedScan(), packageId,
                existing.deliveryAttemptCount(), existing.queuedAt(), existing.nextDeliveryAttemptAt(),
                existing.lastDeliveryErrorCode(), existing.lastDeliveryErrorAt(), existing.submittedAt());
        store.save(updated);
        sessions.put(sessionId, updated);
        return updated;
    }

    public synchronized ScanSession scheduleRetry(UUID sessionId, String safeErrorCode, Instant nextAttemptAt) {
        ScanSession existing = get(sessionId);
        ScanSession queued = copy(existing, ScanSessionStatus.QUEUED, existing.businessIdentifiers(), existing.preparedScan(),
                existing.lexavaultPackageId(), existing.deliveryAttemptCount(), existing.queuedAt(), nextAttemptAt,
                safeErrorCode, clock.instant(), existing.submittedAt());
        store.save(queued);
        sessions.put(sessionId, queued);
        return queued;
    }

    public synchronized ScanSession markAttentionRequired(UUID sessionId, String safeErrorCode) {
        ScanSession existing = get(sessionId);
        ScanSession attention = copy(existing, ScanSessionStatus.ATTENTION_REQUIRED, existing.businessIdentifiers(), existing.preparedScan(),
                existing.lexavaultPackageId(), existing.deliveryAttemptCount(), existing.queuedAt(), null,
                safeErrorCode, clock.instant(), existing.submittedAt());
        store.save(attention);
        sessions.put(sessionId, attention);
        actionableSessionId.compareAndSet(sessionId, null);
        log.warn("SCAN_BRIDGE_DELIVERY_ATTENTION_REQUIRED bridgeId={} siteId={} scannerId={} sessionId={} submissionId={} reasonCode={}",
                existing.bridgeId(), existing.siteId(), existing.scannerId(), existing.sessionId(), existing.submissionId(), safeErrorCode);
        return attention;
    }

    public synchronized ScanSession markSubmitted(UUID sessionId, UUID packageId) {
        ScanSession existing = get(sessionId);
        if (existing.lexavaultPackageId() != null && !existing.lexavaultPackageId().equals(packageId)) {
            throw new IllegalStateException("Scanner submission reconciled to a different LexaVault package id");
        }
        Instant now = clock.instant();
        // Scrub business identifiers as part of the durable success transition. The PDF is disposed only after
        // this durable record proves that LexaVault owns the package.
        ScanSession submitted = copy(existing, ScanSessionStatus.SUBMITTED, Map.of(), existing.preparedScan(), packageId,
                existing.deliveryAttemptCount(), existing.queuedAt(), null, null, null, now);
        store.save(submitted);
        sessions.put(sessionId, submitted);
        actionableSessionId.compareAndSet(sessionId, null);
        if (properties.getDelivery().isCleanupAcceptedPdf()) {
            try {
                artifacts.deletePdfIfPresent(submitted.preparedScan());
                submitted = persistSanitizedTerminalArtifact(submitted);
            } catch (RuntimeException ex) {
                // Keep the original workReference if cleanup fails so startup recovery can retry disposal.
                log.warn("SCAN_BRIDGE_ACCEPTED_PDF_CLEANUP_FAILED sessionId={} packageId={} exceptionType={}",
                        submitted.sessionId(), packageId, ex.getClass().getSimpleName());
            }
        }
        log.info("SCAN_BRIDGE_SUBMISSION_ACCEPTED bridgeId={} siteId={} scannerId={} sessionId={} submissionId={} packageId={} attempts={}",
                submitted.bridgeId(), submitted.siteId(), submitted.scannerId(), submitted.sessionId(), submitted.submissionId(),
                packageId, submitted.deliveryAttemptCount());
        return submitted;
    }

    public synchronized ScanSession cancel(UUID sessionId) {
        ScanSession existing = get(sessionId);
        if (existing.status() == ScanSessionStatus.SUBMITTED) return existing;
        if (existing.status() == ScanSessionStatus.QUEUED || existing.status() == ScanSessionStatus.SUBMITTING) {
            throw new ScanBridgeException("SUBMISSION_ALREADY_QUEUED", "A safely queued scan cannot be cancelled while delivery is in progress.", HttpStatus.CONFLICT);
        }
        if (existing.status() == ScanSessionStatus.CANCELLED) return existing;
        if (existing.status() == ScanSessionStatus.ATTENTION_REQUIRED) {
            throw new ScanBridgeException("ATTENTION_REQUIRED_CANNOT_CANCEL", "A delivery requiring technical attention cannot be cancelled by the operator.", HttpStatus.CONFLICT);
        }
        ScanSession cancelled = copy(existing, ScanSessionStatus.CANCELLED, Map.of(), existing.preparedScan(),
                existing.lexavaultPackageId(), existing.deliveryAttemptCount(), existing.queuedAt(), null,
                existing.lastDeliveryErrorCode(), existing.lastDeliveryErrorAt(), existing.submittedAt());
        store.save(cancelled);
        sessions.put(sessionId, cancelled);
        actionableSessionId.compareAndSet(sessionId, null);
        try {
            artifacts.deletePdfIfPresent(cancelled.preparedScan());
            cancelled = persistSanitizedTerminalArtifact(cancelled);
        } catch (RuntimeException ex) {
            // Keep the original workReference if cleanup fails so startup recovery can retry disposal.
            log.warn("SCAN_BRIDGE_CANCELLED_PDF_CLEANUP_FAILED sessionId={} exceptionType={}",
                    cancelled.sessionId(), ex.getClass().getSimpleName());
        }
        return cancelled;
    }

    public List<ScanSession> dueForDelivery(Instant now) {
        return sessions.values().stream()
                .filter(session -> session.status() == ScanSessionStatus.QUEUED)
                .filter(session -> session.nextDeliveryAttemptAt() == null || !session.nextDeliveryAttemptAt().isAfter(now))
                .sorted(Comparator.comparing(session -> session.queuedAt() == null ? session.createdAt() : session.queuedAt()))
                .toList();
    }

    /**
     * Privacy-safe delivery diagnostics for support/operations. Business identifiers, original
     * filenames and local paths are intentionally excluded.
     */
    public List<ScannerDeliveryItemResponse> deliveryDiagnostics() {
        return sessions.values().stream()
                .filter(session -> session.status() == ScanSessionStatus.QUEUED
                        || session.status() == ScanSessionStatus.SUBMITTING
                        || session.status() == ScanSessionStatus.ATTENTION_REQUIRED)
                .sorted(Comparator.comparing(ScanSession::updatedAt).reversed())
                .map(this::deliveryDiagnostic)
                .toList();
    }

    /**
     * Requeues the same retained ATTENTION_REQUIRED package after an operator/support issue has
     * been corrected. If a LexaVault package id is already known, a missing local PDF does not
     * block this transition because the delivery worker must reconcile server state before it
     * decides whether the PDF is still needed.
     */
    public synchronized ScanSession retryAttentionRequired(UUID sessionId) {
        ScanSession existing = get(sessionId);
        if (existing.status() != ScanSessionStatus.ATTENTION_REQUIRED) {
            throw new ScanBridgeException("DELIVERY_NOT_ATTENTION_REQUIRED",
                    "Only scanner deliveries requiring technical attention can be retried.",
                    HttpStatus.CONFLICT);
        }
        if (existing.lexavaultPackageId() == null
                && (existing.preparedScan() == null || !artifacts.exists(existing.preparedScan()))) {
            throw new ScanBridgeException("LOCAL_SCAN_ARTIFACT_MISSING",
                    "The retained scanner PDF is not available for retry.", HttpStatus.CONFLICT);
        }

        Instant now = clock.instant();
        ScanSession queued = copy(existing, ScanSessionStatus.QUEUED, existing.businessIdentifiers(),
                existing.preparedScan(), existing.lexavaultPackageId(), existing.deliveryAttemptCount(),
                existing.queuedAt() == null ? now : existing.queuedAt(), now,
                existing.lastDeliveryErrorCode(), existing.lastDeliveryErrorAt(), existing.submittedAt());
        store.save(queued);
        sessions.put(sessionId, queued);
        log.info("SCAN_BRIDGE_DELIVERY_REQUEUED bridgeId={} siteId={} scannerId={} sessionId={} submissionId={} priorReasonCode={}",
                queued.bridgeId(), queued.siteId(), queued.scannerId(), queued.sessionId(), queued.submissionId(),
                queued.lastDeliveryErrorCode());
        return queued;
    }

    public long count(ScanSessionStatus status) {
        return sessions.values().stream().filter(session -> session.status() == status).count();
    }

    public UUID activeSessionId() { return actionableSessionId.get(); }

    private ScannerDeliveryItemResponse deliveryDiagnostic(ScanSession session) {
        PreparedScan prepared = session.preparedScan();
        return new ScannerDeliveryItemResponse(
                session.sessionId(), session.submissionId(), prepared == null ? null : prepared.sourceDocumentId(),
                session.status(), session.captureMode(), session.bridgeId(), session.siteId(), session.scannerId(),
                session.lexavaultPackageId(), prepared == null ? 0L : prepared.sizeBytes(),
                session.deliveryAttemptCount(), session.queuedAt(), session.nextDeliveryAttemptAt(),
                session.lastDeliveryErrorCode(), session.lastDeliveryErrorAt(), session.submittedAt());
    }

    /**
     * Deletes only old SUBMITTED/CANCELLED technical records. ATTENTION_REQUIRED and every state
     * that can still own a PDF are intentionally excluded.
     */
    public synchronized void purgeExpiredTerminalSessions(Instant now) {
        long retentionHours = Math.max(1, properties.getDelivery().getTerminalStateRetentionHours());
        Instant cutoff = now.minusSeconds(Math.multiplyExact(retentionHours, 3600L));
        for (ScanSession session : List.copyOf(sessions.values())) {
            if (session.status() != ScanSessionStatus.SUBMITTED && session.status() != ScanSessionStatus.CANCELLED) continue;
            Instant terminalAt = session.status() == ScanSessionStatus.SUBMITTED && session.submittedAt() != null
                    ? session.submittedAt() : session.updatedAt();
            if (terminalAt == null || terminalAt.isAfter(cutoff)) continue;
            store.deleteSession(session.sessionId());
            sessions.remove(session.sessionId());
            actionableSessionId.compareAndSet(session.sessionId(), null);
            log.info("SCAN_BRIDGE_TERMINAL_STATE_PURGED sessionId={} status={}", session.sessionId(), session.status());
        }
    }

    private void cleanupTerminalArtifacts() {
        for (ScanSession session : List.copyOf(sessions.values())) {
            boolean cleanup = session.status() == ScanSessionStatus.CANCELLED
                    || (properties.getDelivery().isCleanupAcceptedPdf() && session.status() == ScanSessionStatus.SUBMITTED);
            if (!cleanup || session.preparedScan() == null || session.preparedScan().workReference() == null) continue;
            try {
                artifacts.deletePdfIfPresent(session.preparedScan());
                persistSanitizedTerminalArtifact(session);
            } catch (RuntimeException ex) {
                log.warn("SCAN_BRIDGE_TERMINAL_ARTIFACT_CLEANUP_FAILED sessionId={} status={} exceptionType={}",
                        session.sessionId(), session.status(), ex.getClass().getSimpleName());
            }
        }
    }

    /**
     * Once the bridge no longer owns the PDF, retain only non-content technical facts needed for
     * short-lived reconciliation/support. Original filenames and local paths can contain business
     * information, so they are removed from terminal state after successful artifact disposal.
     */
    private ScanSession persistSanitizedTerminalArtifact(ScanSession session) {
        PreparedScan prepared = session.preparedScan();
        if (prepared == null || prepared.workReference() == null) return session;
        PreparedScan sanitized = new PreparedScan(prepared.sourceDocumentId(), "scan.pdf", prepared.sizeBytes(),
                prepared.sha256(), prepared.receivedAt(), null, prepared.pageCount());
        ScanSession updated = copy(session, session.status(), session.businessIdentifiers(), sanitized,
                session.lexavaultPackageId(), session.deliveryAttemptCount(), session.queuedAt(),
                session.nextDeliveryAttemptAt(), session.lastDeliveryErrorCode(), session.lastDeliveryErrorAt(),
                session.submittedAt());
        store.save(updated);
        sessions.put(updated.sessionId(), updated);
        return updated;
    }

    private ScanSession copy(ScanSession existing, ScanSessionStatus status, Map<String, String> identifiers,
                             PreparedScan preparedScan, UUID packageId, int attempts, Instant queuedAt,
                             Instant nextAttemptAt, String errorCode, Instant errorAt, Instant submittedAt) {
        return new ScanSession(existing.sessionId(), existing.submissionId(), existing.tenantId(), existing.bridgeId(),
                existing.siteId(), existing.scannerId(), existing.captureMode(), status, identifiers, existing.createdAt(),
                clock.instant(), preparedScan, packageId, attempts, queuedAt, nextAttemptAt, errorCode, errorAt, submittedAt);
    }

    private Map<String, String> sanitizeBusinessIdentifiers(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (key.isEmpty() || value.isEmpty()) continue;
            // Transport safety only. LexaVault owns identifier semantics, normalization and validation.
            if (key.length() > 100 || value.length() > 512) {
                throw new ScanBridgeException("INVALID_BUSINESS_IDENTIFIER_INPUT",
                        "Business identifier key or value exceeds the supported transport size.", HttpStatus.BAD_REQUEST);
            }
            result.put(key, value);
        }
        return Map.copyOf(result);
    }

    private boolean isOperatorActionable(ScanSessionStatus status) {
        return status == ScanSessionStatus.WAITING_FOR_SCAN || status == ScanSessionStatus.READY;
    }
}
