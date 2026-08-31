package com.securedoc.platform.api.platform;

public record PlatformHealthItem(
        String key,
        String label,
        String category,
        String provider,
        String status,
        boolean configured,
        String detail) {
}
