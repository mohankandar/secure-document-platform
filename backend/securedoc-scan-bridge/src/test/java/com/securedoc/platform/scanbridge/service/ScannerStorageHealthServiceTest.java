package com.securedoc.platform.scanbridge.service;

import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ScannerStorageHealthServiceTest {
    @TempDir Path temp;

    @Test
    void healthyWorkRootReportsUsableSpaceAndConfiguredLimits() {
        ScanBridgeProperties properties = properties();
        properties.getStorage().setMinimumUsableSpaceBytes(1);
        properties.getStorage().setMaxScanPdfSizeBytes(123_456_789L);
        ScannerStorageHealthService service = new ScannerStorageHealthService(properties);

        var snapshot = service.snapshot();

        assertTrue(snapshot.healthy());
        assertTrue(snapshot.usableSpaceBytes() > 0);
        assertEquals(1, snapshot.minimumUsableSpaceBytes());
        assertEquals(123_456_789L, snapshot.maxScanPdfSizeBytes());
        assertDoesNotThrow(service::assertReadyForNewScan);
    }

    @Test
    void lowUsableSpaceFailsClosedBeforeStartingNewScan() {
        ScanBridgeProperties properties = properties();
        properties.getStorage().setMinimumUsableSpaceBytes(Long.MAX_VALUE);
        ScannerStorageHealthService service = new ScannerStorageHealthService(properties);

        ScanBridgeException error = assertThrows(ScanBridgeException.class, service::assertReadyForNewScan);

        assertEquals("SCANNER_STORAGE_UNAVAILABLE", error.errorCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
        assertFalse(service.snapshot().healthy());
    }

    @Test
    void oversizedIncomingPdfIsRejectedWithPayloadTooLarge() {
        ScanBridgeProperties properties = properties();
        properties.getStorage().setMaxScanPdfSizeBytes(100);
        ScannerStorageHealthService service = new ScannerStorageHealthService(properties);

        assertDoesNotThrow(() -> service.assertIncomingSize(100));
        ScanBridgeException error = assertThrows(ScanBridgeException.class, () -> service.assertIncomingSize(101));

        assertEquals("SCAN_PDF_TOO_LARGE", error.errorCode());
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, error.status());
    }

    private ScanBridgeProperties properties() {
        ScanBridgeProperties properties = new ScanBridgeProperties();
        properties.setWorkPath(temp.resolve("work").toString());
        return properties;
    }
}
