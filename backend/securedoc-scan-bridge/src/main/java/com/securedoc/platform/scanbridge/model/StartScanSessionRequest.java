package com.securedoc.platform.scanbridge.model;

import java.util.Map;

/**
 * Operator-entered business identifier values for one local scan.
 * Definitions and authoritative business validation remain owned by LexaVault.
 *
 * <p>{@code caseSensitivityLevel} is the optional case level chosen on New case (task 0021). The bridge only carries
 * it; LexaVault reads it (an unknown value is taken as Standard there, never refused).
 */
public record StartScanSessionRequest(
        Map<String, String> businessIdentifiers,
        String caseSensitivityLevel,
        /** Task 0073: the optional priority chosen on New case; LexaVault reads it. */
        String casePriority,
        /** Task 0061: the keys whose values are sensitive; the bridge keeps those in memory only. */
        java.util.List<String> sensitiveFields
) {
    /** Case field values are in no text form of this. */
    @Override
    public String toString() {
        return "StartScanSessionRequest[]";
    }

    public StartScanSessionRequest(Map<String, String> businessIdentifiers, String caseSensitivityLevel, String casePriority) {
        this(businessIdentifiers, caseSensitivityLevel, casePriority, null);
    }

    public StartScanSessionRequest(Map<String, String> businessIdentifiers, String caseSensitivityLevel) {
        this(businessIdentifiers, caseSensitivityLevel, null, null);
    }

    public StartScanSessionRequest(Map<String, String> businessIdentifiers) {
        this(businessIdentifiers, null, null, null);
    }
}
