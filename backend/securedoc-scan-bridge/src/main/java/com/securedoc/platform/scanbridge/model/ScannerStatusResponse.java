package com.securedoc.platform.scanbridge.model;

import java.time.Instant;
import java.util.UUID;

public record ScannerStatusResponse(
        boolean enabled,
        String bridgeId,
        String siteId,
        String scannerId,
        ScannerCaptureMode captureMode,
        UUID bridgeInstanceId,
        String bridgeVersion,
        boolean acquisitionAvailable,
        boolean storageHealthy,
        long usableSpaceBytes,
        long minimumUsableSpaceBytes,
        long maxScanPdfSizeBytes,
        boolean busy,
        UUID activeSessionId,
        int queuedCount,
        int attentionRequiredCount,
        Instant checkedAt
) { }
