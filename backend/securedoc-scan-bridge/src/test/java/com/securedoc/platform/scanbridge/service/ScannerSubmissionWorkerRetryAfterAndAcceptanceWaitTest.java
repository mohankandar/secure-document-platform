package com.securedoc.platform.scanbridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.model.PreparedScan;
import com.securedoc.platform.scanbridge.model.ScanSession;
import com.securedoc.platform.scanbridge.model.ScanSessionStatus;
import com.securedoc.platform.scanbridge.model.StartScanSessionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Task 0024: 429 with Retry-After is retried within the cap; acceptance pending waits up to a time limit. */
class ScannerSubmissionWorkerRetryAfterAndAcceptanceWaitTest {
    @TempDir Path temp;
    private final Instant start = Instant.parse("2026-11-20T12:00:00Z");
    private final MovableClock clock = new MovableClock(start);

    // ---- 429 -------------------------------------------------------------------------------------

    @Test
    void a429WithRetryAfterInSecondsWaitsThatLong() throws Exception {
        Fixture f = fixture();
        UUID id = queued(f);
        when(f.client.createIntake(any(), eq(false))).thenThrow(rateLimited(Duration.ofSeconds(120)));

        f.worker.deliverOne(id);

        var s = f.sessions.get(id);
        assertEquals(ScanSessionStatus.QUEUED, s.status());
        assertEquals("LEXAVAULT_HTTP_429", s.lastDeliveryErrorCode());
        assertEquals(start.plusSeconds(120), s.nextDeliveryAttemptAt());
    }

    @Test
    void a429WithRetryAfterAsADateWaitsUntilThatDate() throws Exception {
        // The client turns an HTTP-date into the wait until it (see the classification test); here 90 seconds.
        Fixture f = fixture();
        UUID id = queued(f);
        Duration untilDate = LexaVaultScannerDeliveryClient.retryAfter("Fri, 20 Nov 2026 12:01:30 GMT", start);
        when(f.client.createIntake(any(), eq(false))).thenThrow(rateLimited(untilDate));

        f.worker.deliverOne(id);

        assertEquals(start.plusSeconds(90), f.sessions.get(id).nextDeliveryAttemptAt());
    }

    @Test
    void a429RetryAfterThatIsTooLargeIsBounded() throws Exception {
        Fixture f = fixture();
        UUID id = queued(f);
        when(f.client.createIntake(any(), eq(false))).thenThrow(rateLimited(Duration.ofSeconds(Long.MAX_VALUE)));

        f.worker.deliverOne(id);

        assertEquals(start.plusSeconds(300), f.sessions.get(id).nextDeliveryAttemptAt(), "default bound 5 minutes");
    }

    @Test
    void a429RetryAfterBoundIsConfigurable() throws Exception {
        Fixture f = fixture();
        f.props.getDelivery().setRetryAfterMaxSeconds(60);
        UUID id = queued(f);
        when(f.client.createIntake(any(), eq(false))).thenThrow(rateLimited(Duration.ofSeconds(3600)));

        f.worker.deliverOne(id);

        assertEquals(start.plusSeconds(60), f.sessions.get(id).nextDeliveryAttemptAt());
    }

    @Test
    void a429WithoutRetryAfterUsesTheExistingSpacing() throws Exception {
        Fixture f = fixture();
        UUID id = queued(f);
        when(f.client.createIntake(any(), eq(false))).thenThrow(rateLimited(null));

        f.worker.deliverOne(id);
        assertEquals(start.plusMillis(2000), f.sessions.get(id).nextDeliveryAttemptAt());
        f.worker.deliverOne(id);
        assertEquals(start.plusMillis(4000), f.sessions.get(id).nextDeliveryAttemptAt());
    }

    @Test
    void a429WithAZeroRetryAfterUsesTheExistingSpacing() throws Exception {
        Fixture f = fixture();
        UUID id = queued(f);
        when(f.client.createIntake(any(), eq(false))).thenThrow(rateLimited(Duration.ZERO));

        f.worker.deliverOne(id);

        assertEquals(start.plusMillis(2000), f.sessions.get(id).nextDeliveryAttemptAt());
    }

    @Test
    void a429ThreeTimesAfterTheFirstAttemptThenNeedsAPerson() throws Exception {
        Fixture f = fixture();
        UUID id = queued(f);
        when(f.client.createIntake(any(), eq(false))).thenThrow(rateLimited(Duration.ofSeconds(10)));

        for (int retry = 1; retry <= ScannerSubmissionWorker.MAX_AUTOMATIC_RETRIES; retry++) {
            f.worker.deliverOne(id);
            assertEquals(ScanSessionStatus.QUEUED, f.sessions.get(id).status(), "attempt " + retry);
        }
        f.worker.deliverOne(id);

        var attention = f.sessions.get(id);
        assertEquals(ScanSessionStatus.ATTENTION_REQUIRED, attention.status());
        assertEquals("LEXAVAULT_HTTP_429", attention.lastDeliveryErrorCode());
        assertEquals(ScannerSubmissionWorker.MAX_AUTOMATIC_RETRIES + 1, attention.deliveryAttemptCount());
        assertTrue(f.artifacts.exists(attention.preparedScan()), "the PDF stays for the person");
        verify(f.client, times(ScannerSubmissionWorker.MAX_AUTOMATIC_RETRIES + 1)).createIntake(any(), eq(false));
    }

    @Test
    void anotherClientErrorIsStillNotRetried() throws Exception {
        Fixture f = fixture();
        UUID id = queued(f);
        when(f.client.createIntake(any(), eq(false))).thenThrow(
                LexaVaultScannerDeliveryClient.DeliveryException.terminal("INTAKE_UNKNOWN_IDENTIFIER", null));

        f.worker.deliverOne(id);

        var attention = f.sessions.get(id);
        assertEquals(ScanSessionStatus.ATTENTION_REQUIRED, attention.status());
        assertEquals("INTAKE_UNKNOWN_IDENTIFIER", attention.lastDeliveryErrorCode());
        verify(f.client, times(1)).createIntake(any(), eq(false));
    }

    // ---- acceptance pending ---------------------------------------------------------------------

    @Test
    void pendingForLessThanTheLimitThenAcceptedCountsNoFailure() throws Exception {
        Fixture f = fixture();
        UUID id = queued(f);
        UUID packageId = pendingLexaVault(f);

        for (int check = 0; check < 6; check++) {
            f.worker.deliverOne(id);
            var waiting = f.sessions.get(id);
            assertEquals(ScanSessionStatus.QUEUED, waiting.status(), "check " + check + " keeps waiting");
            assertEquals(ScannerSubmissionWorker.ACCEPTANCE_PENDING, waiting.lastDeliveryErrorCode());
            assertEquals(0, waiting.deliveryAttemptCount(), "waiting is not a failure");
            assertEquals(start, waiting.acceptancePendingSince(), "the wait keeps its start");
            clock.set(waiting.nextDeliveryAttemptAt());
        }
        assertTrue(Duration.between(start, clock.instant()).toSeconds() < 600);
        when(f.client.status(packageId)).thenReturn(accepted(packageId));

        f.worker.deliverOne(id);

        var submitted = f.sessions.get(id);
        assertEquals(ScanSessionStatus.SUBMITTED, submitted.status());
        assertNull(submitted.acceptancePendingSince());
        assertEquals(1, submitted.deliveryAttemptCount(), "only the attempt that succeeded is counted");
    }

    @Test
    void pendingChecksUseTheExistingSpacingAndNeverPassTheLimit() throws Exception {
        Fixture f = fixture();
        UUID id = queued(f);
        pendingLexaVault(f);

        f.worker.deliverOne(id);
        assertEquals(start.plusMillis(2000), f.sessions.get(id).nextDeliveryAttemptAt(), "first check: initial delay");

        clock.set(start.plusSeconds(590));
        f.worker.deliverOne(id);
        assertEquals(start.plusSeconds(600), f.sessions.get(id).nextDeliveryAttemptAt(),
                "spacing is capped at the maximum delay and stops at the limit");
    }

    @Test
    void pendingPastTheLimitNeedsAPersonWithAClearCode() throws Exception {
        Fixture f = fixture();
        UUID id = queued(f);
        pendingLexaVault(f);

        int checks = 0;
        while (f.sessions.get(id).status() == ScanSessionStatus.QUEUED) {
            assertTrue(++checks < 100, "the wait must end");
            f.worker.deliverOne(id);
            var s = f.sessions.get(id);
            if (s.nextDeliveryAttemptAt() != null) clock.set(s.nextDeliveryAttemptAt());
        }

        var attention = f.sessions.get(id);
        assertEquals(ScanSessionStatus.ATTENTION_REQUIRED, attention.status());
        assertEquals("LEXAVAULT_ACCEPTANCE_WAIT_EXPIRED", attention.lastDeliveryErrorCode());
        assertEquals(start.plusSeconds(600), clock.instant(), "default limit 10 minutes");
        assertTrue(checks > ScannerSubmissionWorker.MAX_AUTOMATIC_RETRIES + 1, "more checks than the failure cap");
        assertTrue(f.artifacts.exists(attention.preparedScan()));
        assertEquals("LEXAVAULT_ACCEPTANCE_WAIT_EXPIRED", f.sessions.deliveryDiagnostics().get(0).lastDeliveryErrorCode());
    }

    @Test
    void theWaitLimitIsConfigurable() throws Exception {
        Fixture f = fixture();
        f.props.getDelivery().setAcceptanceWaitLimitSeconds(30);
        UUID id = queued(f);
        pendingLexaVault(f);

        f.worker.deliverOne(id);
        clock.set(start.plusSeconds(30));
        f.worker.deliverOne(id);

        assertEquals("LEXAVAULT_ACCEPTANCE_WAIT_EXPIRED", f.sessions.get(id).lastDeliveryErrorCode());
    }

    @Test
    void pendingMixedWithARealFailureCountsOnlyTheFailure() throws Exception {
        Fixture f = fixture();
        UUID id = queued(f);
        UUID packageId = pendingLexaVault(f);

        f.worker.deliverOne(id);                                    // pending
        clock.set(f.sessions.get(id).nextDeliveryAttemptAt());
        when(f.client.status(packageId)).thenThrow(
                LexaVaultScannerDeliveryClient.DeliveryException.retryable("LEXAVAULT_HTTP_503", null));
        f.worker.deliverOne(id);                                    // real failure
        var afterFailure = f.sessions.get(id);
        assertEquals("LEXAVAULT_HTTP_503", afterFailure.lastDeliveryErrorCode());
        assertEquals(1, afterFailure.deliveryAttemptCount(), "the failure counts");
        assertEquals(start, afterFailure.acceptancePendingSince(), "the wait clock keeps running");

        clock.set(afterFailure.nextDeliveryAttemptAt());
        doReturn(awaiting(packageId)).when(f.client).status(packageId);
        f.worker.deliverOne(id);                                    // pending again
        var waiting = f.sessions.get(id);
        assertEquals(ScannerSubmissionWorker.ACCEPTANCE_PENDING, waiting.lastDeliveryErrorCode());
        assertEquals(1, waiting.deliveryAttemptCount(), "pending adds nothing to the count");

        clock.set(waiting.nextDeliveryAttemptAt());
        doReturn(accepted(packageId)).when(f.client).status(packageId);
        f.worker.deliverOne(id);
        assertEquals(ScanSessionStatus.SUBMITTED, f.sessions.get(id).status());
    }

    @Test
    void realFailuresDuringTheWaitStillReachTheCap() throws Exception {
        Fixture f = fixture();
        UUID id = queued(f);
        UUID packageId = pendingLexaVault(f);
        f.worker.deliverOne(id);                                    // pending
        when(f.client.status(packageId)).thenThrow(
                LexaVaultScannerDeliveryClient.DeliveryException.retryable("LEXAVAULT_UNAVAILABLE", null));

        for (int i = 0; i <= ScannerSubmissionWorker.MAX_AUTOMATIC_RETRIES; i++) {
            clock.set(f.sessions.get(id).nextDeliveryAttemptAt());
            f.worker.deliverOne(id);
        }

        var attention = f.sessions.get(id);
        assertEquals(ScanSessionStatus.ATTENTION_REQUIRED, attention.status());
        assertEquals("LEXAVAULT_UNAVAILABLE", attention.lastDeliveryErrorCode());
    }

    @Test
    void aPersonsRetryStartsAFreshWait() throws Exception {
        Fixture f = fixture();
        f.props.getDelivery().setAcceptanceWaitLimitSeconds(30);
        UUID id = queued(f);
        pendingLexaVault(f);
        f.worker.deliverOne(id);
        clock.set(start.plusSeconds(30));
        f.worker.deliverOne(id);
        assertEquals(ScanSessionStatus.ATTENTION_REQUIRED, f.sessions.get(id).status());

        f.sessions.retryAttentionRequired(id);
        assertNull(f.sessions.get(id).acceptancePendingSince());
        f.worker.deliverOne(id);

        var waiting = f.sessions.get(id);
        assertEquals(ScanSessionStatus.QUEUED, waiting.status());
        assertEquals(start.plusSeconds(30), waiting.acceptancePendingSince());
    }

    @Test
    void theWaitSurvivesARestart() throws Exception {
        Fixture f = fixture();
        UUID id = queued(f);
        pendingLexaVault(f);
        f.worker.deliverOne(id);

        Fixture restarted = fixtureWithClient(f.client);
        assertEquals(start, restarted.sessions.get(id).acceptancePendingSince());
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static LexaVaultScannerDeliveryClient.DeliveryException rateLimited(Duration retryAfter) {
        return LexaVaultScannerDeliveryClient.DeliveryException.retryable("LEXAVAULT_HTTP_429", null, retryAfter);
    }

    /** LexaVault creates the package and takes the upload, but reports it not yet accepted. */
    private UUID pendingLexaVault(Fixture f) {
        UUID packageId = UUID.randomUUID();
        when(f.client.createIntake(any(), eq(false))).thenReturn(awaiting(packageId));
        when(f.client.upload(any(), any())).thenReturn(
                new LexaVaultScannerDeliveryClient.DocumentReceipt(packageId, "AWAITING_DOCUMENTS", true));
        when(f.client.status(packageId)).thenReturn(awaiting(packageId));
        return packageId;
    }

    private static LexaVaultScannerDeliveryClient.IntakeStatus awaiting(UUID packageId) {
        return new LexaVaultScannerDeliveryClient.IntakeStatus(packageId, "AWAITING_DOCUMENTS", "CREATED");
    }

    private static LexaVaultScannerDeliveryClient.IntakeStatus accepted(UUID packageId) {
        return new LexaVaultScannerDeliveryClient.IntakeStatus(packageId,
                LexaVaultScannerDeliveryClient.ACCEPTED_FOR_PROCESSING, "SUBMITTED");
    }

    private UUID queued(Fixture f) throws Exception {
        var waiting = f.sessions.start(new StartScanSessionRequest(Map.of("contract_number", "000900009901")));
        Path pdf = f.store.sessionDirectory(waiting.sessionId()).resolve("scan.pdf");
        Files.write(pdf, "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII));
        ScanSession ready = f.sessions.markReady(waiting.sessionId(), new PreparedScan(UUID.randomUUID(), "scan.pdf",
                Files.size(pdf), "a".repeat(64), start, waiting.sessionId() + "/scan.pdf"));
        f.sessions.queueForSubmission(ready.sessionId());
        return ready.sessionId();
    }

    private Fixture fixture() { return fixtureWithClient(mock(LexaVaultScannerDeliveryClient.class)); }

    private Fixture fixtureWithClient(LexaVaultScannerDeliveryClient client) {
        ScanBridgeProperties props = new ScanBridgeProperties();
        ScanBridgeProperties.Simulator simulator = new ScanBridgeProperties.Simulator();
        simulator.setInboxPath(temp.resolve("inbox").toString());
        props.setWorkPath(temp.resolve("work").toString());
        props.setProblemPath(temp.resolve("problem").toString());
        props.setSimulator(simulator);
        FileScanSessionStore store = new FileScanSessionStore(props, new ObjectMapper().findAndRegisterModules());
        ScanArtifactService artifacts = new ScanArtifactService(store);
        ScanSessionService sessions = new ScanSessionService(props, store, artifacts, clock);
        sessions.recoverDurableState();
        ScannerSubmissionWorker worker = new ScannerSubmissionWorker(props, sessions, artifacts, client, clock);
        return new Fixture(props, sessions, store, artifacts, client, worker);
    }

    private record Fixture(ScanBridgeProperties props, ScanSessionService sessions, FileScanSessionStore store,
                           ScanArtifactService artifacts, LexaVaultScannerDeliveryClient client,
                           ScannerSubmissionWorker worker) { }

    private static final class MovableClock extends Clock {
        private Instant now;
        MovableClock(Instant now) { this.now = now; }
        void set(Instant instant) { this.now = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
