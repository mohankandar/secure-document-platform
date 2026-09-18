package com.securedoc.platform.scanbridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.model.PreparedScan;
import com.securedoc.platform.scanbridge.model.ScanSessionStatus;
import com.securedoc.platform.scanbridge.model.StartScanSessionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScannerSubmissionWorkerTest {
    @TempDir Path temp;
    private final Instant now = Instant.parse("2026-09-10T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @Test
    void acceptedUploadMarksSubmittedScrubsIdentifiersAndDeletesPdf() throws Exception {
        Fixture f = fixture();
        var session = readySession(f);
        f.sessions.queueForSubmission(session.sessionId());
        UUID packageId = UUID.randomUUID();
        when(f.client.createIntake(any(), eq(false))).thenReturn(
                new LexaVaultScannerDeliveryClient.IntakeStatus(packageId, "AWAITING_DOCUMENTS", "CREATED"));
        when(f.client.upload(any(), any())).thenReturn(
                new LexaVaultScannerDeliveryClient.DocumentReceipt(packageId,
                        LexaVaultScannerDeliveryClient.ACCEPTED_FOR_PROCESSING, false));

        f.worker.deliverOne(session.sessionId());

        var submitted = f.sessions.get(session.sessionId());
        assertEquals(ScanSessionStatus.SUBMITTED, submitted.status());
        assertEquals(packageId, submitted.lexavaultPackageId());
        assertTrue(submitted.businessIdentifiers().isEmpty());
        assertFalse(f.artifacts.exists(submitted.preparedScan()));
    }

    @Test
    void lostResponseCanReconcileAcceptedPackageWithoutSecondUpload() throws Exception {
        Fixture f = fixture();
        var session = readySession(f);
        f.sessions.queueForSubmission(session.sessionId());
        f.sessions.beginDelivery(session.sessionId());
        UUID packageId = UUID.randomUUID();
        f.sessions.recordLexaVaultPackage(session.sessionId(), packageId);
        // Simulate process restart: SUBMITTING is recovered to QUEUED.
        Fixture restarted = fixtureWithClient(f.client);
        when(f.client.status(packageId)).thenReturn(new LexaVaultScannerDeliveryClient.IntakeStatus(
                packageId, LexaVaultScannerDeliveryClient.ACCEPTED_FOR_PROCESSING, "SUBMITTED"));

        restarted.worker.deliverOne(session.sessionId());

        assertEquals(ScanSessionStatus.SUBMITTED, restarted.sessions.get(session.sessionId()).status());
        verify(f.client, never()).upload(any(), any());
    }

    @Test
    void expiredIncompleteIntakeCreatesRetryAttemptAndUploadsToReplacementPackage() throws Exception {
        Fixture f = fixture();
        var session = readySession(f);
        f.sessions.queueForSubmission(session.sessionId());
        UUID expiredPackageId = UUID.randomUUID();
        f.sessions.recordLexaVaultPackage(session.sessionId(), expiredPackageId);
        UUID replacementPackageId = UUID.randomUUID();

        when(f.client.status(expiredPackageId)).thenReturn(new LexaVaultScannerDeliveryClient.IntakeStatus(
                expiredPackageId, "INCOMPLETE_EXPIRED", "CANCELLED"));
        when(f.client.createIntake(any(), eq(true))).thenReturn(new LexaVaultScannerDeliveryClient.IntakeStatus(
                replacementPackageId, "AWAITING_DOCUMENTS", "CREATED"));
        when(f.client.upload(any(), any())).thenReturn(new LexaVaultScannerDeliveryClient.DocumentReceipt(
                replacementPackageId, LexaVaultScannerDeliveryClient.ACCEPTED_FOR_PROCESSING, false));

        f.worker.deliverOne(session.sessionId());

        var submitted = f.sessions.get(session.sessionId());
        assertEquals(ScanSessionStatus.SUBMITTED, submitted.status());
        assertEquals(replacementPackageId, submitted.lexavaultPackageId());
        verify(f.client).createIntake(any(), eq(true));
        verify(f.client).upload(argThat(s -> replacementPackageId.equals(s.lexavaultPackageId())), any());
    }

    @Test
    void reconciledAcceptanceWinsEvenIfLocalPdfWasAlreadyLost() throws Exception {
        Fixture f = fixture();
        var session = readySession(f);
        f.sessions.queueForSubmission(session.sessionId());
        UUID packageId = UUID.randomUUID();
        f.sessions.recordLexaVaultPackage(session.sessionId(), packageId);
        Files.delete(f.artifacts.resolve(session.preparedScan()));
        when(f.client.status(packageId)).thenReturn(new LexaVaultScannerDeliveryClient.IntakeStatus(
                packageId, LexaVaultScannerDeliveryClient.ACCEPTED_FOR_PROCESSING, "SUBMITTED"));

        f.worker.deliverOne(session.sessionId());

        assertEquals(ScanSessionStatus.SUBMITTED, f.sessions.get(session.sessionId()).status());
        verify(f.client, never()).upload(any(), any());
    }

    @Test
    void retryableNetworkFailureKeepsPdfDurablyQueued() throws Exception {
        Fixture f = fixture();
        var session = readySession(f);
        f.sessions.queueForSubmission(session.sessionId());
        when(f.client.createIntake(any(), eq(false))).thenThrow(
                LexaVaultScannerDeliveryClient.DeliveryException.retryable("LEXAVAULT_UNAVAILABLE", null));

        f.worker.deliverOne(session.sessionId());

        var queued = f.sessions.get(session.sessionId());
        assertEquals(ScanSessionStatus.QUEUED, queued.status());
        assertEquals("LEXAVAULT_UNAVAILABLE", queued.lastDeliveryErrorCode());
        assertNotNull(queued.nextDeliveryAttemptAt());
        assertTrue(f.artifacts.exists(queued.preparedScan()));
    }

    @Test
    void terminalAuthorizationFailureMovesToAttentionWithoutDeletingPdf() throws Exception {
        Fixture f = fixture();
        var session = readySession(f);
        f.sessions.queueForSubmission(session.sessionId());
        when(f.client.createIntake(any(), eq(false))).thenThrow(
                LexaVaultScannerDeliveryClient.DeliveryException.terminal("ACCESS_DENIED", null));

        f.worker.deliverOne(session.sessionId());

        var attention = f.sessions.get(session.sessionId());
        assertEquals(ScanSessionStatus.ATTENTION_REQUIRED, attention.status());
        assertEquals("ACCESS_DENIED", attention.lastDeliveryErrorCode());
        assertTrue(f.artifacts.exists(attention.preparedScan()));
    }

    private com.securedoc.platform.scanbridge.model.ScanSession readySession(Fixture f) throws Exception {
        var waiting = f.sessions.start(new StartScanSessionRequest(Map.of("contract_number", "000900009901")));
        Path pdf = f.store.sessionDirectory(waiting.sessionId()).resolve("scan.pdf");
        Files.write(pdf, minimalPdf());
        return f.sessions.markReady(waiting.sessionId(), new PreparedScan(UUID.randomUUID(), "scan.pdf", Files.size(pdf),
                "a".repeat(64), now, waiting.sessionId() + "/scan.pdf"));
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
        return new Fixture(sessions, store, artifacts, client, worker);
    }

    private byte[] minimalPdf() {
        return "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private record Fixture(ScanSessionService sessions, FileScanSessionStore store, ScanArtifactService artifacts,
                           LexaVaultScannerDeliveryClient client, ScannerSubmissionWorker worker) { }
}
