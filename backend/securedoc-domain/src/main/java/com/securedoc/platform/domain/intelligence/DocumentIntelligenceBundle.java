package com.securedoc.platform.domain.intelligence;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable version descriptor for document-intelligence behavior.
 *
 * <p>The bundle deliberately stores only safe version identifiers. Provider endpoints,
 * credentials, prompts, document content, OCR text and model responses do not belong here.</p>
 */
public record DocumentIntelligenceBundle(
        String version,
        IntelligenceBundleStatus status,
        String modelVersion,
        String promptVersion,
        String detectorVersion,
        String ocrRulesVersion,
        String profileVersion,
        String validationRulesVersion,
        String thresholdsVersion,
        String description,
        Instant createdAt,
        Instant promotedAt) {

    public static final String R11_BASELINE_VERSION = "r11-baseline-v1";

    public DocumentIntelligenceBundle {
        version = requireText(version, "Intelligence bundle version", 80);
        Objects.requireNonNull(status, "Intelligence bundle status is required");
        modelVersion = normalizeVersion(modelVersion);
        promptVersion = normalizeVersion(promptVersion);
        detectorVersion = normalizeVersion(detectorVersion);
        ocrRulesVersion = normalizeVersion(ocrRulesVersion);
        profileVersion = normalizeVersion(profileVersion);
        validationRulesVersion = normalizeVersion(validationRulesVersion);
        thresholdsVersion = normalizeVersion(thresholdsVersion);
        description = normalizeOptional(description, 500);
        Objects.requireNonNull(createdAt, "Intelligence bundle created time is required");
        if (status == IntelligenceBundleStatus.PROMOTED && promotedAt == null) {
            throw new IllegalArgumentException("Promoted intelligence bundle requires promoted time");
        }
    }

    /** New tenant selections may use only promoted bundles. */
    public boolean selectableForTenant() {
        return status == IntelligenceBundleStatus.PROMOTED;
    }

    /**
     * A tenant that is already pinned to a promoted or retired bundle may continue to execute it.
     * RETIRED means "no new selections", not "break existing pins/rollback targets".
     */
    public boolean executableForPinnedTenant() {
        return status != IntelligenceBundleStatus.CANDIDATE;
    }

    private static String normalizeVersion(String value) {
        return requireText(value, "Intelligence component version", 120);
    }

    private static String requireText(String value, String label, int maxLength) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + " is required");
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(label + " is too long");
        return normalized;
    }

    private static String normalizeOptional(String value, int maxLength) {
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException("Value is too long");
        return normalized;
    }
}
