package com.securedoc.platform.scanbridge.model;

import java.time.Instant;
import java.util.UUID;

/** Privacy-safe local delivery diagnostic; intentionally excludes business identifiers, filename and local path. */
public record ScannerDeliveryItemResponse(
        UUID sessionId,
        UUID submissionId,
        UUID sourceDocumentId,
        ScanSessionStatus status,
        ScannerCaptureMode captureMode,
        String bridgeId,
        String siteId,
        String scannerId,
        UUID lexavaultPackageId,
        long sizeBytes,
        int deliveryAttemptCount,
        Instant queuedAt,
        Instant nextDeliveryAttemptAt,
        String lastDeliveryErrorCode,
        Instant lastDeliveryErrorAt,
        Instant submittedAt
) { }
