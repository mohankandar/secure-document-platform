package com.securedoc.platform.scanbridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.model.ScanSessionStatus;
import com.securedoc.platform.scanbridge.model.ScannerCaptureMode;
import com.securedoc.platform.scanbridge.model.StartScanSessionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PhysicalScannerAcquisitionAdapterTest {
    @TempDir Path temp;

    @Test
    void physicalAcquisitionPublishesOneDurableReadyPdfIntoExistingSessionLifecycle() throws Exception {
        ScanBridgeProperties properties = properties();
        FileScanSessionStore store = new FileScanSessionStore(properties, new ObjectMapper().findAndRegisterModules());
        ScanArtifactService artifacts = new ScanArtifactService(store);
        ScanSessionService sessions = new ScanSessionService(properties, store, artifacts);
        sessions.recoverDurableState();
        ScannerStorageHealthService storage = new ScannerStorageHealthService(properties);
        WindowsWiaScannerClient wia = mock(WindowsWiaScannerClient.class);
        PhysicalScanPdfAssembler assembler = mock(PhysicalScanPdfAssembler.class);
        PhysicalScannerAcquisitionAdapter adapter = new PhysicalScannerAcquisitionAdapter(
                properties, sessions, store, storage, wia, assembler);

        var waiting = sessions.start(new StartScanSessionRequest(Map.of("contract_number", "000900009901")));
        when(wia.acquire(any(Path.class))).thenReturn(List.of(Path.of("page-0001.bmp"), Path.of("page-0002.bmp")));
        when(assembler.assemble(anyList(), any(Path.class), eq(300))).thenAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            Files.write(target, minimalPdf());
            return 2;
        });

        adapter.acquire(waiting);

        var ready = sessions.get(waiting.sessionId());
        assertEquals(ScanSessionStatus.READY, ready.status());
        assertNotNull(ready.preparedScan());
        assertEquals(2, ready.preparedScan().pageCount());
        assertTrue(artifacts.exists(ready.preparedScan()));
        assertEquals(ScannerCaptureMode.PHYSICAL, ready.captureMode());
        verify(wia).acquire(store.sessionDirectory(waiting.sessionId()).resolve("physical-capture"));
    }

    @Test
    void physicalAvailabilityNeverFallsBackToSimulator() {
        ScanBridgeProperties properties = properties();
        FileScanSessionStore store = new FileScanSessionStore(properties, new ObjectMapper().findAndRegisterModules());
        ScanArtifactService artifacts = new ScanArtifactService(store);
        ScanSessionService sessions = new ScanSessionService(properties, store, artifacts);
        sessions.recoverDurableState();
        ScannerStorageHealthService storage = new ScannerStorageHealthService(properties);
        WindowsWiaScannerClient wia = mock(WindowsWiaScannerClient.class);
        PhysicalScanPdfAssembler assembler = mock(PhysicalScanPdfAssembler.class);
        when(wia.available()).thenReturn(false);

        PhysicalScannerAcquisitionAdapter adapter = new PhysicalScannerAcquisitionAdapter(
                properties, sessions, store, storage, wia, assembler);

        assertFalse(adapter.available());
        verify(wia).available();
    }

    private ScanBridgeProperties properties() {
        ScanBridgeProperties properties = new ScanBridgeProperties();
        properties.setMode(ScannerCaptureMode.PHYSICAL);
        properties.setWorkPath(temp.resolve("work").toString());
        properties.setProblemPath(temp.resolve("problem").toString());
        ScanBridgeProperties.Storage storage = new ScanBridgeProperties.Storage();
        storage.setMinimumUsableSpaceBytes(1);
        storage.setMaxScanPdfSizeBytes(10 * 1024 * 1024);
        properties.setStorage(storage);
        return properties;
    }

    private byte[] minimalPdf() {
        return "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    }
}
