package com.securedoc.platform.application.telemetry;

public enum ProductTelemetryMode {
    OFF,
    BASIC,
    ENHANCED;

    public static ProductTelemetryMode parse(String value) {
        if (value == null || value.isBlank()) return OFF;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return OFF;
        }
    }
}
