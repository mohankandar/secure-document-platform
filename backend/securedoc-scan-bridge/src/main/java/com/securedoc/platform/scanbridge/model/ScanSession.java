package com.securedoc.platform.scanbridge.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Durable local Scanner Intake state.
 *
 * <p>Business identifiers are retained only until LexaVault has definitively accepted the
 * package, then scrubbed from the local durable record. The prepared PDF is retained until
 * the same acceptance boundary and may then be removed.</p>
 */
public record ScanSession(
        UUID sessionId,
        UUID submissionId,
        String tenantId,
        String bridgeId,
        String siteId,
        String scannerId,
        ScannerCaptureMode captureMode,
        ScanSessionStatus status,
        Map<String, String> businessIdentifiers,
        Instant createdAt,
        Instant updatedAt,
        PreparedScan preparedScan,
        UUID lexavaultPackageId,
        int deliveryAttemptCount,
        Instant queuedAt,
        Instant nextDeliveryAttemptAt,
        String lastDeliveryErrorCode,
        Instant lastDeliveryErrorAt,
        Instant submittedAt
) {
    public ScanSession {
        businessIdentifiers = businessIdentifiers == null ? Map.of() : Map.copyOf(businessIdentifiers);
    }
}
