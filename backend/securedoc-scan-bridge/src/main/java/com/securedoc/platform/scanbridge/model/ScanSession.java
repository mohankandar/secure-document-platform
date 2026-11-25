package com.securedoc.platform.scanbridge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

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
@JsonIgnoreProperties(value = {"lastDeliveryLvCode"}, allowGetters = true)
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
        Instant submittedAt,
        /** Task 0021: the case level chosen on New case, carried to LexaVault as sent (null when none). */
        String caseSensitivityLevel,
        /**
         * Task 0024: start of the current wait for LexaVault to accept a document it already has; null when
         * not waiting. Waiting does not count toward the automatic retry cap; it is bounded by
         * {@code delivery.acceptance-wait-limit-seconds} instead.
         */
        Instant acceptancePendingSince,
        /**
         * Task 0025: the HTTP status of LexaVault's answer that gave {@code lastDeliveryErrorCode}; null when the code
         * is the bridge's own or the state file predates it. Used only to work out {@link #lastDeliveryLvCode()}.
         */
        Integer lastDeliveryHttpStatus,
        /** Task 0025: LexaVault's own LV code from its error body (the detail under LV-SCN-016); null when none. */
        String lastDeliveryLexaVaultCode,
        /** Task 0073: the priority chosen on New case, carried to LexaVault as sent (null when none). */
        String casePriority
) {
    public ScanSession {
        businessIdentifiers = businessIdentifiers == null ? Map.of() : Map.copyOf(businessIdentifiers);
    }

    /** Task 0061: stands for a sensitive value, which the bridge keeps in memory only (never in this state). */
    public static final String HIDDEN = "\u2022".repeat(6);

    /** Case field values are in no text form of this. */
    @Override
    public String toString() {
        return "ScanSession[sessionId=" + sessionId + ", status=" + status + "]";
    }

    /** The state as before task 0025, with no LexaVault answer details. */
    public ScanSession(UUID sessionId, UUID submissionId, String tenantId, String bridgeId, String siteId, String scannerId,
                       ScannerCaptureMode captureMode, ScanSessionStatus status, Map<String, String> businessIdentifiers,
                       Instant createdAt, Instant updatedAt, PreparedScan preparedScan, UUID lexavaultPackageId,
                       int deliveryAttemptCount, Instant queuedAt, Instant nextDeliveryAttemptAt,
                       String lastDeliveryErrorCode, Instant lastDeliveryErrorAt, Instant submittedAt,
                       String caseSensitivityLevel, Instant acceptancePendingSince) {
        this(sessionId, submissionId, tenantId, bridgeId, siteId, scannerId, captureMode, status, businessIdentifiers,
                createdAt, updatedAt, preparedScan, lexavaultPackageId, deliveryAttemptCount, queuedAt,
                nextDeliveryAttemptAt, lastDeliveryErrorCode, lastDeliveryErrorAt, submittedAt, caseSensitivityLevel,
                acceptancePendingSince, null, null, null);
    }

    /**
     * Task 0025: the LV code for {@code lastDeliveryErrorCode}, worked out from the state each time. It appears in
     * the JSON written, but is ignored when state is read, so old state files need nothing new. Null when there is
     * no delivery error code.
     */
    @JsonProperty(value = "lastDeliveryLvCode", access = JsonProperty.Access.READ_ONLY)
    public String lastDeliveryLvCode() {
        BridgeLvCode lv = BridgeLvCodes.forDelivery(lastDeliveryErrorCode, lastDeliveryHttpStatus, status);
        return lv == null ? null : lv.code();
    }
}
