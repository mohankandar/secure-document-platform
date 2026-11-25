package com.securedoc.platform.scanbridge.service;

import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.model.PreparedScan;
import com.securedoc.platform.scanbridge.model.ScanSessionStatus;
import com.securedoc.platform.scanbridge.model.StartScanSessionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class ScanSessionServiceTest {
    @TempDir Path temp;
    private final Instant now = Instant.parse("2026-09-09T20:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @Test
    void startsOneWaitingSessionWithOperatorBusinessIdentifiersAndPersistsIt() {
        Fixture fixture = fixture();
        var session = fixture.service.start(new StartScanSessionRequest(Map.of("contractNumber", "000900009901")));

        assertEquals(ScanSessionStatus.WAITING_FOR_SCAN, session.status());
        assertEquals("000900009901", session.businessIdentifiers().get("contractNumber"));
        assertNotNull(session.submissionId());
        assertEquals(session.sessionId(), fixture.service.activeSessionId());
        assertTrue(Files.exists(fixture.store.sessionDirectory(session.sessionId()).resolve(FileScanSessionStore.STATE_FILE)));
    }

    /** Task 0061: a sensitive case field is sent, and never in the state file, the API answer or a text form. */
    @Test
    void aSensitiveValueIsKeptInMemoryOnlyAndIsGoneAfterARestart() throws Exception {
        Fixture fixture = fixture();
        var session = fixture.service.start(new StartScanSessionRequest(
                Map.of("contractNumber", "1", "borrower_name", "Synthetic Quill"), null, null, List.of("borrower_name")));
        assertEquals(com.securedoc.platform.scanbridge.model.ScanSession.HIDDEN, session.businessIdentifiers().get("borrower_name"));
        assertFalse(Files.readString(fixture.store.sessionDirectory(session.sessionId())
                .resolve(FileScanSessionStore.STATE_FILE)).contains("Quill"));
        assertFalse(session.toString().contains("Quill"));
        var sending = fixture.service.withSensitiveValues(session);
        assertEquals("Synthetic Quill", sending.businessIdentifiers().get("borrower_name"));
        assertFalse(sending.toString().contains("Quill"));

        Fixture restarted = fixture();
        var lost = assertThrows(LexaVaultScannerDeliveryClient.DeliveryException.class,
                () -> restarted.service.withSensitiveValues(restarted.service.get(session.sessionId())));
        assertEquals("SENSITIVE_FIELDS_LOST", lost.safeCode());
        assertFalse(lost.retryable());
        assertEquals("LV-SCN-022", com.securedoc.platform.scanbridge.model.BridgeLvCodes.forDelivery("SENSITIVE_FIELDS_LOST", null,
                ScanSessionStatus.ATTENTION_REQUIRED).code());
    }

    /** Task 0021: the New case "Sensitivity" choice is carried as sent, survives a restart and every state change. */
    @Test
    void carriesTheCaseLevelChosenOnNewCaseThroughRestartAndReady() throws Exception {
        Fixture first = fixture();
        var waiting = first.service.start(new StartScanSessionRequest(Map.of("contractNumber", "1"), "CONFIDENTIAL"));
        assertEquals("CONFIDENTIAL", waiting.caseSensitivityLevel());

        Fixture restarted = fixture();
        Path pdf = restarted.store.sessionDirectory(waiting.sessionId()).resolve("scan.pdf");
        Files.write(pdf, minimalPdf());
        var ready = restarted.service.markReady(waiting.sessionId(),
                new PreparedScan(UUID.randomUUID(), "scan.pdf", Files.size(pdf), "abc", now, waiting.sessionId() + "/scan.pdf"));
        assertEquals("CONFIDENTIAL", ready.caseSensitivityLevel());
    }

    @Test
    void noChoiceIsCarriedAsNothingSent() {
        Fixture fixture = fixture();
        assertNull(fixture.service.start(new StartScanSessionRequest(Map.of())).caseSensitivityLevel());
    }

    @Test
    void rejectsSecondConcurrentWaitingSessionForSameScanner() {
        Fixture fixture = fixture();
        fixture.service.start(new StartScanSessionRequest(Map.of()));
        ScanBridgeException error = assertThrows(ScanBridgeException.class,
                () -> fixture.service.start(new StartScanSessionRequest(Map.of())));
        assertEquals("SCANNER_BUSY", error.errorCode());
        assertEquals(HttpStatus.CONFLICT, error.status());
    }

    @Test
    void readySessionRetainsSameSubmissionAndBusinessIdentifiers() throws Exception {
        Fixture fixture = fixture();
        var waiting = fixture.service.start(new StartScanSessionRequest(Map.of("memberNumber", "00900009901")));
        Path pdf = fixture.store.sessionDirectory(waiting.sessionId()).resolve("scan.pdf");
        Files.write(pdf, minimalPdf());
        PreparedScan prepared = new PreparedScan(UUID.randomUUID(), "scan.pdf", Files.size(pdf), "abc", now,
                waiting.sessionId() + "/scan.pdf");

        var ready = fixture.service.markReady(waiting.sessionId(), prepared);
        assertEquals(ScanSessionStatus.READY, ready.status());
        assertEquals(waiting.submissionId(), ready.submissionId());
        assertEquals("00900009901", ready.businessIdentifiers().get("memberNumber"));
        assertEquals(ready.sessionId(), fixture.service.activeSessionId());
        assertEquals(ready.sessionId(), fixture.service.currentActionableSession().orElseThrow().sessionId());
        ScanBridgeException busy = assertThrows(ScanBridgeException.class,
                () -> fixture.service.start(new StartScanSessionRequest(Map.of())));
        assertEquals("SCANNER_BUSY", busy.errorCode());
    }

    @Test
    void restartRecoversWaitingReadyAndQueuedState() throws Exception {
        Fixture first = fixture();
        var waiting = first.service.start(new StartScanSessionRequest(Map.of("contractNumber", "000900009901")));

        Fixture waitingRestart = fixture();
        assertEquals(waiting.sessionId(), waitingRestart.service.activeSessionId());

        Path pdf = waitingRestart.store.sessionDirectory(waiting.sessionId()).resolve("scan.pdf");
        Files.write(pdf, minimalPdf());
        var ready = waitingRestart.service.markReady(waiting.sessionId(),
                new PreparedScan(UUID.randomUUID(), "scan.pdf", Files.size(pdf), "abc", now, waiting.sessionId() + "/scan.pdf"));

        Fixture readyRestart = fixture();
        assertEquals(ScanSessionStatus.READY, readyRestart.service.get(ready.sessionId()).status());
        assertEquals(ready.sessionId(), readyRestart.service.currentActionableSession().orElseThrow().sessionId());
        readyRestart.service.queueForSubmission(ready.sessionId());
        assertTrue(readyRestart.service.currentActionableSession().isEmpty());

        Fixture queuedRestart = fixture();
        assertEquals(ScanSessionStatus.QUEUED, queuedRestart.service.get(ready.sessionId()).status());
        assertEquals(1, queuedRestart.service.dueForDelivery(now).size());
    }

    @Test
    void submittingStateRecoversToQueuedForIdempotentReconciliation() throws Exception {
        Fixture fixture = fixture();
        var waiting = fixture.service.start(new StartScanSessionRequest(Map.of()));
        Path pdf = fixture.store.sessionDirectory(waiting.sessionId()).resolve("scan.pdf");
        Files.write(pdf, minimalPdf());
        fixture.service.markReady(waiting.sessionId(), new PreparedScan(UUID.randomUUID(), "scan.pdf", Files.size(pdf),
                "abc", now, waiting.sessionId() + "/scan.pdf"));
        fixture.service.queueForSubmission(waiting.sessionId());
        fixture.service.beginDelivery(waiting.sessionId());
        UUID packageId = UUID.randomUUID();
        fixture.service.recordLexaVaultPackage(waiting.sessionId(), packageId);

        Fixture restarted = fixture();
        assertEquals(ScanSessionStatus.QUEUED, restarted.service.get(waiting.sessionId()).status());
        assertEquals(packageId, restarted.service.get(waiting.sessionId()).lexavaultPackageId());
        assertEquals("RECOVERED_AFTER_RESTART", restarted.service.get(waiting.sessionId()).lastDeliveryErrorCode());
    }

    @Test
    void durableQueueHandoffReleasesScannerForNextPhysicalPackage() throws Exception {
        Fixture fixture = fixture();
        var first = fixture.service.start(new StartScanSessionRequest(Map.of("contractNumber", "000900009901")));
        Path firstPdf = fixture.store.sessionDirectory(first.sessionId()).resolve("scan.pdf");
        Files.write(firstPdf, minimalPdf());
        fixture.service.markReady(first.sessionId(), new PreparedScan(UUID.randomUUID(), "scan.pdf", Files.size(firstPdf),
                "abc", now, first.sessionId() + "/scan.pdf"));

        var queued = fixture.service.queueForSubmission(first.sessionId());
        var second = fixture.service.start(new StartScanSessionRequest(Map.of("contractNumber", "000900009902")));

        assertEquals(ScanSessionStatus.QUEUED, queued.status());
        assertEquals(ScanSessionStatus.QUEUED, fixture.service.get(first.sessionId()).status());
        assertEquals(ScanSessionStatus.WAITING_FOR_SCAN, second.status());
        assertEquals(second.sessionId(), fixture.service.activeSessionId());
        assertTrue(Files.exists(firstPdf), "Queued package must retain its PDF until LexaVault acceptance");
    }

    @Test
    void acceptedSubmissionScrubsBusinessIdentifiersAndDeletesLocalPdf() throws Exception {
        Fixture fixture = fixture();
        var waiting = fixture.service.start(new StartScanSessionRequest(Map.of("contractNumber", "000900009901")));
        Path pdf = fixture.store.sessionDirectory(waiting.sessionId()).resolve("scan.pdf");
        Files.write(pdf, minimalPdf());
        fixture.service.markReady(waiting.sessionId(), new PreparedScan(UUID.randomUUID(), "scan.pdf", Files.size(pdf),
                "abc", now, waiting.sessionId() + "/scan.pdf"));
        fixture.service.queueForSubmission(waiting.sessionId());
        fixture.service.beginDelivery(waiting.sessionId());
        UUID packageId = UUID.randomUUID();
        fixture.service.recordLexaVaultPackage(waiting.sessionId(), packageId);

        var submitted = fixture.service.markSubmitted(waiting.sessionId(), packageId);
        assertEquals(ScanSessionStatus.SUBMITTED, submitted.status());
        assertTrue(submitted.businessIdentifiers().isEmpty());
        assertFalse(Files.exists(pdf));
        assertEquals(packageId, submitted.lexavaultPackageId());
        assertEquals("scan.pdf", submitted.preparedScan().fileName());
        assertNull(submitted.preparedScan().workReference());
        assertEquals("abc", submitted.preparedScan().sha256());
    }

    @Test
    void cancelledReadySessionScrubsBusinessIdentifiersAndLocalArtifactReference() throws Exception {
        Fixture fixture = fixture();
        var waiting = fixture.service.start(new StartScanSessionRequest(Map.of("contractNumber", "000900009901")));
        Path pdf = fixture.store.sessionDirectory(waiting.sessionId()).resolve("customer-contract-000900009901.pdf");
        Files.write(pdf, minimalPdf());
        fixture.service.markReady(waiting.sessionId(), new PreparedScan(UUID.randomUUID(),
                "customer-contract-000900009901.pdf", Files.size(pdf), "abc", now,
                waiting.sessionId() + "/customer-contract-000900009901.pdf"));

        var cancelled = fixture.service.cancel(waiting.sessionId());

        assertEquals(ScanSessionStatus.CANCELLED, cancelled.status());
        assertTrue(cancelled.businessIdentifiers().isEmpty());
        assertFalse(Files.exists(pdf));
        assertEquals("scan.pdf", cancelled.preparedScan().fileName());
        assertNull(cancelled.preparedScan().workReference());
    }

    /** Task 0077: the id of a request is an equal id, never the same object; cancelling left the scanner busy for ever. */
    @Test
    void anIdFromARequestFreesTheScannerForTheNextScan() throws Exception {
        Fixture fixture = fixture();
        var first = fixture.service.start(new StartScanSessionRequest(Map.of("contractNumber", "000900009901")));
        fixture.service.cancel(UUID.fromString(first.sessionId().toString()));
        assertNull(fixture.service.activeSessionId());

        var second = fixture.service.start(new StartScanSessionRequest(Map.of("contractNumber", "000900009902")));
        Path pdf = fixture.store.sessionDirectory(second.sessionId()).resolve("scan.pdf");
        Files.write(pdf, minimalPdf());
        fixture.service.markReady(second.sessionId(), new PreparedScan(UUID.randomUUID(), "scan.pdf", Files.size(pdf), "abc",
                now, second.sessionId() + "/scan.pdf"));
        fixture.service.queueForSubmission(UUID.fromString(second.sessionId().toString()));
        assertNull(fixture.service.activeSessionId());

        assertEquals(ScanSessionStatus.WAITING_FOR_SCAN, fixture.service
                .start(new StartScanSessionRequest(Map.of("contractNumber", "000900009903"))).status());
    }

    @Test
    void oldSubmittedTechnicalStateIsPurgedButAttentionStateIsNeverAutoPurged() throws Exception {
        Fixture fixture = fixture();
        var waiting = fixture.service.start(new StartScanSessionRequest(Map.of("contractNumber", "000900009901")));
        Path pdf = fixture.store.sessionDirectory(waiting.sessionId()).resolve("scan.pdf");
        Files.write(pdf, minimalPdf());
        fixture.service.markReady(waiting.sessionId(), new PreparedScan(UUID.randomUUID(), "scan.pdf", Files.size(pdf),
                "abc", now, waiting.sessionId() + "/scan.pdf"));
        fixture.service.queueForSubmission(waiting.sessionId());
        fixture.service.beginDelivery(waiting.sessionId());
        UUID packageId = UUID.randomUUID();
        fixture.service.recordLexaVaultPackage(waiting.sessionId(), packageId);
        fixture.service.markSubmitted(waiting.sessionId(), packageId);

        fixture.service.purgeExpiredTerminalSessions(now.plusSeconds(73 * 3600L));
        assertFalse(Files.exists(fixture.store.sessionDirectory(waiting.sessionId())));
        assertThrows(ScanBridgeException.class, () -> fixture.service.get(waiting.sessionId()));

        var second = fixture.service.start(new StartScanSessionRequest(Map.of()));
        Path secondPdf = fixture.store.sessionDirectory(second.sessionId()).resolve("scan.pdf");
        Files.write(secondPdf, minimalPdf());
        fixture.service.markReady(second.sessionId(), new PreparedScan(UUID.randomUUID(), "scan.pdf", Files.size(secondPdf),
                "abc", now, second.sessionId() + "/scan.pdf"));
        fixture.service.markAttentionRequired(second.sessionId(), "ACCESS_DENIED");
        fixture.service.purgeExpiredTerminalSessions(now.plusSeconds(365L * 24 * 3600));
        assertEquals(ScanSessionStatus.ATTENTION_REQUIRED, fixture.service.get(second.sessionId()).status());
        assertTrue(Files.exists(secondPdf));
    }

    @Test
    void attentionRequiredDeliveryCanBeRequeuedWithoutChangingSubmissionIdentity() throws Exception {
        Fixture fixture = fixture();
        var waiting = fixture.service.start(new StartScanSessionRequest(Map.of("contractNumber", "000900009901")));
        Path pdf = fixture.store.sessionDirectory(waiting.sessionId()).resolve("scan.pdf");
        Files.write(pdf, minimalPdf());
        UUID sourceDocumentId = UUID.randomUUID();
        fixture.service.markReady(waiting.sessionId(), new PreparedScan(sourceDocumentId, "scan.pdf", Files.size(pdf),
                "abc", now, waiting.sessionId() + "/scan.pdf"));
        fixture.service.queueForSubmission(waiting.sessionId());
        fixture.service.beginDelivery(waiting.sessionId());
        fixture.service.markAttentionRequired(waiting.sessionId(), "LEXAVAULT_ACCESS_DENIED");

        var retried = fixture.service.retryAttentionRequired(waiting.sessionId());

        assertEquals(ScanSessionStatus.QUEUED, retried.status());
        assertEquals(waiting.submissionId(), retried.submissionId());
        assertEquals(sourceDocumentId, retried.preparedScan().sourceDocumentId());
        assertEquals("LEXAVAULT_ACCESS_DENIED", retried.lastDeliveryErrorCode());
        assertEquals(now, retried.nextDeliveryAttemptAt());
        assertEquals(0, retried.deliveryAttemptCount(), "task 0023 D2: a person's retry restarts the capped retries");
        assertTrue(Files.exists(pdf));
        assertEquals(1, fixture.service.dueForDelivery(now).size());
    }

    @Test
    void attentionRetryFailsClosedWhenBridgeStillOwnsNoRecoverablePdf() throws Exception {
        Fixture fixture = fixture();
        var waiting = fixture.service.start(new StartScanSessionRequest(Map.of()));
        Path pdf = fixture.store.sessionDirectory(waiting.sessionId()).resolve("scan.pdf");
        Files.write(pdf, minimalPdf());
        fixture.service.markReady(waiting.sessionId(), new PreparedScan(UUID.randomUUID(), "scan.pdf", Files.size(pdf),
                "abc", now, waiting.sessionId() + "/scan.pdf"));
        fixture.service.queueForSubmission(waiting.sessionId());
        fixture.service.beginDelivery(waiting.sessionId());
        fixture.service.markAttentionRequired(waiting.sessionId(), "DELIVERY_FAILED");
        Files.delete(pdf);

        ScanBridgeException error = assertThrows(ScanBridgeException.class,
                () -> fixture.service.retryAttentionRequired(waiting.sessionId()));

        assertEquals("LOCAL_SCAN_ARTIFACT_MISSING", error.errorCode());
        assertEquals(HttpStatus.CONFLICT, error.status());
    }

    @Test
    void attentionRetryWithKnownLexaVaultPackageAllowsReconciliationEvenIfLocalPdfIsGone() throws Exception {
        Fixture fixture = fixture();
        var waiting = fixture.service.start(new StartScanSessionRequest(Map.of()));
        Path pdf = fixture.store.sessionDirectory(waiting.sessionId()).resolve("scan.pdf");
        Files.write(pdf, minimalPdf());
        fixture.service.markReady(waiting.sessionId(), new PreparedScan(UUID.randomUUID(), "scan.pdf", Files.size(pdf),
                "abc", now, waiting.sessionId() + "/scan.pdf"));
        fixture.service.queueForSubmission(waiting.sessionId());
        fixture.service.beginDelivery(waiting.sessionId());
        UUID packageId = UUID.randomUUID();
        fixture.service.recordLexaVaultPackage(waiting.sessionId(), packageId);
        fixture.service.markAttentionRequired(waiting.sessionId(), "LEXAVAULT_STATUS_UNAVAILABLE");
        Files.delete(pdf);

        var retried = fixture.service.retryAttentionRequired(waiting.sessionId());

        assertEquals(ScanSessionStatus.QUEUED, retried.status());
        assertEquals(packageId, retried.lexavaultPackageId());
        assertEquals(now, retried.nextDeliveryAttemptAt());
    }

    @Test
    void deliveryDiagnosticsExcludeBusinessValuesFilenameAndLocalPath() throws Exception {
        Fixture fixture = fixture();
        var waiting = fixture.service.start(new StartScanSessionRequest(Map.of("contractNumber", "000900009901")));
        Path pdf = fixture.store.sessionDirectory(waiting.sessionId()).resolve("customer-000900009901.pdf");
        Files.write(pdf, minimalPdf());
        UUID sourceDocumentId = UUID.randomUUID();
        fixture.service.markReady(waiting.sessionId(), new PreparedScan(sourceDocumentId,
                "customer-000900009901.pdf", Files.size(pdf), "abc", now,
                waiting.sessionId() + "/customer-000900009901.pdf"));
        fixture.service.queueForSubmission(waiting.sessionId());
        fixture.service.beginDelivery(waiting.sessionId());
        fixture.service.markAttentionRequired(waiting.sessionId(), "DELIVERY_POLICY_REJECTED");

        var diagnostics = fixture.service.deliveryDiagnostics();
        String json = JsonMapper.builderWithJackson2Defaults().build().writeValueAsString(diagnostics);

        assertEquals(1, diagnostics.size());
        assertEquals(sourceDocumentId, diagnostics.get(0).sourceDocumentId());
        assertEquals(ScanSessionStatus.ATTENTION_REQUIRED, diagnostics.get(0).status());
        assertFalse(json.contains("000900009901"));
        assertFalse(json.contains("customer-"));
        assertFalse(json.contains("workReference"));
        assertFalse(json.contains("businessIdentifiers"));
    }

    /** Task 0023 B9 (#91): a failed delete used to be retried only at the next bridge start. */
    @Test
    void aCancelledPdfWhoseDeleteFailedIsDeletedOnALaterCycle() throws Exception {
        Fixture fixture = fixture();
        var waiting = fixture.service.start(new StartScanSessionRequest(Map.of()));
        // A non-empty directory in place of the PDF makes the delete fail the way a locked file does.
        Path pdf = fixture.store.sessionDirectory(waiting.sessionId()).resolve("scan.pdf");
        Files.createDirectories(pdf);
        Path blocker = pdf.resolve("lock");
        Files.write(blocker, minimalPdf());
        fixture.service.markReady(waiting.sessionId(), new PreparedScan(UUID.randomUUID(), "scan.pdf", 1,
                "abc", now, waiting.sessionId() + "/scan.pdf"));

        var cancelled = fixture.service.cancel(waiting.sessionId());
        assertEquals(ScanSessionStatus.CANCELLED, cancelled.status());
        assertNotNull(cancelled.preparedScan().workReference(), "the reference stays so the delete can be retried");

        Files.delete(blocker);
        fixture.service.purgeExpiredTerminalSessions(now);

        assertFalse(Files.exists(pdf));
        assertNull(fixture.service.get(waiting.sessionId()).preparedScan().workReference());
    }

    @Test
    void aPurgeThatCannotDeleteOneSessionKeepsItAndCarriesOnWithTheOthers() throws Exception {
        ScanBridgeProperties props = properties();
        FileScanSessionStore store = spy(new FileScanSessionStore(props, JsonMapper.builderWithJackson2Defaults().build()));
        ScanArtifactService artifacts = new ScanArtifactService(store);
        ScanSessionService service = new ScanSessionService(props, store, artifacts, clock);
        service.recoverDurableState();
        var first = service.start(new StartScanSessionRequest(Map.of()));
        service.cancel(first.sessionId());
        var second = service.start(new StartScanSessionRequest(Map.of()));
        service.cancel(second.sessionId());
        doThrow(new IllegalStateException("Unable to remove expired Scanner Bridge state"))
                .when(store).deleteSession(first.sessionId());

        assertDoesNotThrow(() -> service.purgeExpiredTerminalSessions(now.plusSeconds(365L * 24 * 3600)));

        assertEquals(ScanSessionStatus.CANCELLED, service.get(first.sessionId()).status(), "kept for the next cycle");
        assertThrows(ScanBridgeException.class, () -> service.get(second.sessionId()));
    }

    private Fixture fixture() {
        ScanBridgeProperties props = properties();
        ObjectMapper mapper = JsonMapper.builderWithJackson2Defaults().build();
        FileScanSessionStore store = new FileScanSessionStore(props, mapper);
        ScanArtifactService artifacts = new ScanArtifactService(store);
        ScanSessionService service = new ScanSessionService(props, store, artifacts, clock);
        service.recoverDurableState();
        return new Fixture(service, store, artifacts);
    }

    private ScanBridgeProperties properties() {
        ScanBridgeProperties props = new ScanBridgeProperties();
        ScanBridgeProperties.Simulator simulator = new ScanBridgeProperties.Simulator();
        simulator.setInboxPath(temp.resolve("inbox").toString());
        props.setWorkPath(temp.resolve("work").toString());
        props.setProblemPath(temp.resolve("problem").toString());
        simulator.setStableWindowMs(0);
        props.setSimulator(simulator);
        return props;
    }

    private byte[] minimalPdf() {
        return "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private record Fixture(ScanSessionService service, FileScanSessionStore store, ScanArtifactService artifacts) { }
}
