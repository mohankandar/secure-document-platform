package com.securedoc.platform.scanbridge.model;

import java.util.Map;

/**
 * Operator-entered business identifier values for one local scan.
 * Definitions and authoritative business validation remain owned by LexaVault.
 */
public record StartScanSessionRequest(
        Map<String, String> businessIdentifiers
) { }
