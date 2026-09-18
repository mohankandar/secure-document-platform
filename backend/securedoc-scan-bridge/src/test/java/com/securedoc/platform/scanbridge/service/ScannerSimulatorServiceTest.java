package com.securedoc.platform.scanbridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.model.ScanSessionStatus;
import com.securedoc.platform.scanbridge.model.StartScanSessionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScannerSimulatorServiceTest {
    @TempDir Path temp;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-09T20:00:00Z"), ZoneOffset.UTC);

    @Test
    void stablePdfIsClaimedAndPreparedForWaitingSession() throws Exception {
        Fixture f = fixture();
        var waiting = f.sessions.start(new StartScanSessionRequest(Map.of("contractNumber", "000900009901")));
        Path source = f.simulator.inboxPath().resolve("scan.pdf");
        Files.write(source, minimalPdf());
        f.simulator.scanOnce();
        f.simulator.scanOnce();

        var ready = f.sessions.get(waiting.sessionId());
        assertEquals(ScanSessionStatus.READY, ready.status());
        assertEquals("scan.pdf", ready.preparedScan().fileName());
        assertEquals(minimalPdf().length, ready.preparedScan().sizeBytes());
        assertEquals(64, ready.preparedScan().sha256().length());
        assertFalse(Files.exists(source));
        assertTrue(Files.exists(f.simulator.workPath().resolve(ready.preparedScan().workReference())));
    }

    @Test
    void testPdfUploadUsesSameSimulatorInboxAndStableFilePath() throws Exception {
        Fixture f = fixture();
        var waiting = f.sessions.start(new StartScanSessionRequest(Map.of()));
        f.simulator.stageTestPdf(waiting.sessionId(), "qa.pdf", new ByteArrayInputStream(minimalPdf()));
        assertTrue(Files.exists(f.simulator.inboxPath().resolve("qa.pdf")));
        f.simulator.scanOnce();
        f.simulator.scanOnce();
        assertEquals(ScanSessionStatus.READY, f.sessions.get(waiting.sessionId()).status());
    }

    @Test
    void staleInboxFileBlocksStartingMetadataSessionToPreventMisassociation() throws Exception {
        Fixture f = fixture();
        Path source = f.simulator.inboxPath().resolve("waiting.pdf");
        Files.write(source, minimalPdf());
        ScanBridgeException error = assertThrows(ScanBridgeException.class, f.simulator::assertReadyForNewSession);
        assertEquals("SCANNER_INBOX_NOT_EMPTY", error.errorCode());
        assertTrue(Files.exists(source));
    }

    @Test
    void oneWaitingSessionClaimsOnlyOneOfTwoStablePdfs() throws Exception {
        Fixture f = fixture();
        var waiting = f.sessions.start(new StartScanSessionRequest(Map.of("contractNumber", "000900009901")));
        Path first = f.simulator.inboxPath().resolve("a-first.pdf");
        Path second = f.simulator.inboxPath().resolve("b-second.pdf");
        Files.write(first, minimalPdf()); Files.write(second, minimalPdf());
        f.simulator.scanOnce(); f.simulator.scanOnce();
        var ready = f.sessions.get(waiting.sessionId());
        assertEquals("a-first.pdf", ready.preparedScan().fileName());
        assertFalse(Files.exists(first)); assertTrue(Files.exists(second));
    }

    @Test
    void unsupportedAndFakePdfAreRejectedWithoutConsumingWaitingSession() throws Exception {
        Fixture f = fixture();
        var waiting = f.sessions.start(new StartScanSessionRequest(Map.of()));
        Files.writeString(f.simulator.inboxPath().resolve("notes.txt"), "not a pdf");
        f.simulator.scanOnce(); f.simulator.scanOnce();
        assertEquals(ScanSessionStatus.WAITING_FOR_SCAN, f.sessions.get(waiting.sessionId()).status());
        Files.writeString(f.simulator.inboxPath().resolve("fake.pdf"), "this is not a PDF");
        f.simulator.scanOnce(); f.simulator.scanOnce();
        assertEquals(ScanSessionStatus.WAITING_FOR_SCAN, f.sessions.get(waiting.sessionId()).status());
        assertTrue(Files.list(f.simulator.problemPath()).count() >= 4); // rejected files plus reason files
    }

    @Test
    void crashAfterClaimBeforeReadyIsRecoveredFromDurableWorkDirectory() throws Exception {
        Fixture f = fixture();
        var waiting = f.sessions.start(new StartScanSessionRequest(Map.of()));
        Path dir = f.store.sessionDirectory(waiting.sessionId());
        Files.createDirectories(dir);
        Files.write(dir.resolve("claimed.pdf"), minimalPdf());

        Fixture restarted = fixture(false);
        restarted.simulator.initializeDirectories();
        assertEquals(ScanSessionStatus.READY, restarted.sessions.get(waiting.sessionId()).status());
        assertEquals("claimed.pdf", restarted.sessions.get(waiting.sessionId()).preparedScan().fileName());
    }

    @Test
    void simulatorRejectsPdfThatExceedsConfiguredBridgeLimitWithoutLeavingPartFile() throws Exception {
        Fixture f = fixture();
        f.properties.getStorage().setMaxScanPdfSizeBytes(8);
        var waiting = f.sessions.start(new StartScanSessionRequest(Map.of()));

        ScanBridgeException error = assertThrows(ScanBridgeException.class,
                () -> f.simulator.stageTestPdf(waiting.sessionId(), "too-large.pdf",
                        new ByteArrayInputStream(minimalPdf())));

        assertEquals("SCAN_PDF_TOO_LARGE", error.errorCode());
        try (var files = Files.list(f.simulator.inboxPath())) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
        assertEquals(ScanSessionStatus.WAITING_FOR_SCAN, f.sessions.get(waiting.sessionId()).status());
    }

    private Fixture fixture() throws Exception { return fixture(true); }

    private Fixture fixture(boolean initializeSimulator) throws Exception {
        ScanBridgeProperties props = properties();
        FileScanSessionStore store = new FileScanSessionStore(props, new ObjectMapper().findAndRegisterModules());
        ScanArtifactService artifacts = new ScanArtifactService(store);
        ScanSessionService sessions = new ScanSessionService(props, store, artifacts, clock);
        sessions.recoverDurableState();
        ScannerStorageHealthService storageHealth = new ScannerStorageHealthService(props);
        ScannerSimulatorService simulator = new ScannerSimulatorService(props, sessions, storageHealth, clock);
        if (initializeSimulator) simulator.initializeDirectories();
        return new Fixture(sessions, simulator, store, props);
    }

    private ScanBridgeProperties properties() {
        ScanBridgeProperties props = new ScanBridgeProperties();
        ScanBridgeProperties.Simulator simulator = new ScanBridgeProperties.Simulator();
        simulator.setInboxPath(temp.resolve("inbox").toString());
        props.setWorkPath(temp.resolve("work").toString());
        props.setProblemPath(temp.resolve("problem").toString());
        simulator.setStableWindowMs(0);
        props.setSimulator(simulator);
        props.getStorage().setMinimumUsableSpaceBytes(1);
        return props;
    }

    private byte[] minimalPdf() {
        return "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private record Fixture(ScanSessionService sessions, ScannerSimulatorService simulator, FileScanSessionStore store, ScanBridgeProperties properties) { }
}
