package com.securedoc.platform.api.platform;

/**
 * One service in Admin › Health. Task 0026: {@code lvCode} is {@code LV-SYS-006} "Health check failed" when the check
 * failed ({@code UNHEALTHY}), and {@code null} otherwise.
 */
public record PlatformHealthItem(
        String key,
        String label,
        String category,
        String provider,
        String status,
        boolean configured,
        String detail,
        String lvCode) {
}
