package com.securedoc.platform.scanbridge.service;

import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Local durable-storage admission guard for high-volume scanner acquisition. */
@Component
public final class ScannerStorageHealthService {
    private final ScanBridgeProperties properties;

    public ScannerStorageHealthService(ScanBridgeProperties properties) {
        this.properties = properties;
    }

    public Snapshot snapshot() {
        Path workRoot = Path.of(properties.getWorkPath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(workRoot);
            long usable = Files.getFileStore(workRoot).getUsableSpace();
            long minimum = Math.max(1, properties.getStorage().getMinimumUsableSpaceBytes());
            return new Snapshot(usable >= minimum, usable, minimum, maxScanPdfSizeBytes());
        } catch (IOException ex) {
            return new Snapshot(false, -1, Math.max(1, properties.getStorage().getMinimumUsableSpaceBytes()), maxScanPdfSizeBytes());
        }
    }

    public void assertReadyForNewScan() {
        Snapshot state = snapshot();
        if (!state.healthy()) {
            throw new ScanBridgeException("SCANNER_STORAGE_UNAVAILABLE",
                    "Scanner local storage does not have enough usable space for a new package.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public void assertIncomingSize(long sizeBytes) {
        if (sizeBytes < 0 || sizeBytes > maxScanPdfSizeBytes()) {
            throw new ScanBridgeException("SCAN_PDF_TOO_LARGE",
                    "The scanned PDF exceeds the configured Scanner Bridge size limit.",
                    HttpStatus.PAYLOAD_TOO_LARGE);
        }
    }

    public long maxScanPdfSizeBytes() {
        return Math.max(1, properties.getStorage().getMaxScanPdfSizeBytes());
    }

    public record Snapshot(boolean healthy, long usableSpaceBytes, long minimumUsableSpaceBytes,
                           long maxScanPdfSizeBytes) { }
}
