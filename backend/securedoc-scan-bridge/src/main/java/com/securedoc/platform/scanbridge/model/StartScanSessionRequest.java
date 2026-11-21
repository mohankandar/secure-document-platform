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
        String caseSensitivityLevel
) {
    public StartScanSessionRequest(Map<String, String> businessIdentifiers) {
        this(businessIdentifiers, null);
    }
}
