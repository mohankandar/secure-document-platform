package com.securedoc.platform.scanbridge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Task 0025: delivery items carry the LV code beside lastDeliveryErrorCode; states and decisions are unchanged. */
class ScannerDeliveryLvCodeTest {
    @TempDir Path temp;
    private final Instant now = Instant.parse("2026-11-21T09:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void aLexaVaultRefusalIsScn016WithLexaVaultsCodesAsTheDetail() throws Exception {
        Fixture f = fixture();
        UUID id = queuedSession(f);
        when(f.client.createIntake(any(), eq(false))).thenThrow(LexaVaultScannerDeliveryClient.DeliveryException
                .terminal("INTAKE_UNKNOWN_IDENTIFIER", null, 400, "LV-INT-014"));

        f.worker.deliverOne(id);

        ScanSession attention = f.sessions.get(id);
        assertEquals(ScanSessionStatus.ATTENTION_REQUIRED, attention.status());
        assertEquals("INTAKE_UNKNOWN_IDENTIFIER", attention.lastDeliveryErrorCode());
        assertEquals("LV-SCN-016", attention.lastDeliveryLvCode());
        assertEquals("LV-INT-014", attention.lastDeliveryLexaVaultCode());
        var item = f.sessions.deliveryDiagnostics().get(0);
        assertEquals("INTAKE_UNKNOWN_IDENTIFIER", item.lastDeliveryErrorCode());
        assertEquals("LV-SCN-016", item.lastDeliveryLvCode());
        assertEquals("LV-INT-014", item.lastDeliveryLexaVaultCode());

        // A manual retry keeps the same code and detail until the next attempt says otherwise.
        ScanSession requeued = f.sessions.retryAttentionRequired(id);
        assertEquals("LV-SCN-016", requeued.lastDeliveryLvCode());
        assertEquals("LV-INT-014", requeued.lastDeliveryLexaVaultCode());
    }

    @Test
    void aRetriedFailureIsScn015ThenScn018OnceTheRetriesRanOut() throws Exception {
        Fixture f = fixture();
        UUID id = queuedSession(f);
        when(f.client.createIntake(any(), eq(false))).thenThrow(LexaVaultScannerDeliveryClient.DeliveryException
                .retryable("INTERNAL_ERROR", null, null, 500, "LV-SYS-001"));

        f.worker.deliverOne(id);
        ScanSession queued = f.sessions.get(id);
        assertEquals(ScanSessionStatus.QUEUED, queued.status());
        assertEquals("LV-SCN-015", queued.lastDeliveryLvCode());
        assertEquals("LV-SYS-001", queued.lastDeliveryLexaVaultCode());

        for (int i = 0; i < ScannerSubmissionWorker.MAX_AUTOMATIC_RETRIES; i++) f.worker.deliverOne(id);

        ScanSession attention = f.sessions.get(id);
        assertEquals(ScanSessionStatus.ATTENTION_REQUIRED, attention.status());
        assertEquals("INTERNAL_ERROR", attention.lastDeliveryErrorCode());
        assertEquals("LV-SCN-018", attention.lastDeliveryLvCode());
        assertEquals("LV-SCN-018", f.sessions.deliveryDiagnostics().get(0).lastDeliveryLvCode());
    }

    @Test
    void aMissingLocalPdfDuringDeliveryIsScn013() throws Exception {
        Fixture f = fixture();
        UUID id = queuedSession(f);
        f.sessions.beginDelivery(id);
        f.sessions.markAttentionRequired(id, "LOCAL_SCAN_ARTIFACT_MISSING");

        assertEquals("LV-SCN-013", f.sessions.get(id).lastDeliveryLvCode());
        assertNull(f.sessions.get(id).lastDeliveryLexaVaultCode());
    }

    @Test
    void theLvCodeIsInTheJsonButIgnoredWhenStateIsRead() throws Exception {
        Fixture f = fixture();
        UUID id = queuedSession(f);
        f.sessions.beginDelivery(id);
        f.sessions.markAttentionRequired(id, "ACCESS_DENIED", 403, "LV-AUT-003");

        JsonNode json = mapper.readTree(mapper.writeValueAsString(f.sessions.get(id)));
        assertEquals("LV-SCN-016", json.get("lastDeliveryLvCode").asText());
        assertEquals("LV-AUT-003", json.get("lastDeliveryLexaVaultCode").asText());

        // A restarted bridge reads it back (the strict test mapper fails on unknown properties).
        Fixture restarted = fixture();
        assertEquals("LV-SCN-016", restarted.sessions.get(id).lastDeliveryLvCode());
        assertEquals(403, restarted.sessions.get(id).lastDeliveryHttpStatus());
    }

    @Test
    void aStateFileWrittenBeforeTask0025StillLoads() throws Exception {
        Fixture f = fixture();
        UUID id = queuedSession(f);
        f.sessions.beginDelivery(id);
        f.sessions.markAttentionRequired(id, "ACCESS_DENIED");
        Path stateFile = f.store.sessionDirectory(id).resolve(FileScanSessionStore.STATE_FILE);
        ObjectNode old = (ObjectNode) mapper.readTree(Files.readString(stateFile, StandardCharsets.UTF_8));
        old.remove("lastDeliveryHttpStatus");
        old.remove("lastDeliveryLexaVaultCode");
        old.remove("lastDeliveryLvCode");
        Files.writeString(stateFile, mapper.writeValueAsString(old), StandardCharsets.UTF_8);

        Fixture restarted = fixture();
        ScanSession loaded = restarted.sessions.get(id);
        assertEquals(ScanSessionStatus.ATTENTION_REQUIRED, loaded.status());
        assertEquals("ACCESS_DENIED", loaded.lastDeliveryErrorCode());
        assertNull(loaded.lastDeliveryHttpStatus());
        assertNull(loaded.lastDeliveryLexaVaultCode());
        assertEquals("LV-SCN-016", loaded.lastDeliveryLvCode());
    }

    @Test
    void aSessionWithoutDeliveryErrorHasNoLvCode() throws Exception {
        Fixture f = fixture();
        UUID id = queuedSession(f);
        assertNull(f.sessions.get(id).lastDeliveryLvCode());
        assertNull(f.sessions.deliveryDiagnostics().get(0).lastDeliveryLvCode());
    }

    private UUID queuedSession(Fixture f) throws Exception {
        var waiting = f.sessions.start(new StartScanSessionRequest(Map.of("contract_number", "000900009901")));
        Path pdf = f.store.sessionDirectory(waiting.sessionId()).resolve("scan.pdf");
        Files.write(pdf, "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII));
        f.sessions.markReady(waiting.sessionId(), new PreparedScan(UUID.randomUUID(), "scan.pdf", Files.size(pdf),
                "a".repeat(64), now, waiting.sessionId() + "/scan.pdf"));
        f.sessions.queueForSubmission(waiting.sessionId());
        return waiting.sessionId();
    }

    private Fixture fixture() {
        ScanBridgeProperties props = new ScanBridgeProperties();
        ScanBridgeProperties.Simulator simulator = new ScanBridgeProperties.Simulator();
        simulator.setInboxPath(temp.resolve("inbox").toString());
        props.setWorkPath(temp.resolve("work").toString());
        props.setProblemPath(temp.resolve("problem").toString());
        props.setSimulator(simulator);
        FileScanSessionStore store = new FileScanSessionStore(props, mapper);
        ScanArtifactService artifacts = new ScanArtifactService(store);
        ScanSessionService sessions = new ScanSessionService(props, store, artifacts, clock);
        sessions.recoverDurableState();
        LexaVaultScannerDeliveryClient client = mock(LexaVaultScannerDeliveryClient.class);
        ScannerSubmissionWorker worker = new ScannerSubmissionWorker(props, sessions, artifacts, client, clock);
        return new Fixture(sessions, store, client, worker);
    }

    private record Fixture(ScanSessionService sessions, FileScanSessionStore store,
                           LexaVaultScannerDeliveryClient client, ScannerSubmissionWorker worker) { }
}
