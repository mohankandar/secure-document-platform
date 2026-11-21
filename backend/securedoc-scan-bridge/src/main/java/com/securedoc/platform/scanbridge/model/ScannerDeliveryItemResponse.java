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
        /** Task 0025: the LV code for lastDeliveryErrorCode (for example LV-SCN-016); null when there is none. */
        String lastDeliveryLvCode,
        /** Task 0025: LexaVault's own LV code when LexaVault refused (the detail under LV-SCN-016); null when none. */
        String lastDeliveryLexaVaultCode,
        Instant lastDeliveryErrorAt,
        Instant submittedAt
) { }
