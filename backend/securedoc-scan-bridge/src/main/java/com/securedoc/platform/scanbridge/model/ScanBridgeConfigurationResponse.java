package com.securedoc.platform.scanbridge.model;

import java.util.List;
import java.util.UUID;

public record ScanBridgeConfigurationResponse(
        boolean enabled,
        String tenantId,
        String bridgeId,
        String siteId,
        String scannerId,
        ScannerCaptureMode captureMode,
        UUID bridgeInstanceId,
        String bridgeVersion,
        String browserRequestToken,
        String simulatorInboxPath,
        long stableWindowMs,
        long maxScanPdfSizeBytes,
        long minimumUsableSpaceBytes,
        List<String> prohibitedCapabilities
) { }
