package com.securedoc.platform.scanbridge.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Enforces the Scanner Bridge's loopback-only network boundary.
 *
 * <p>The browser request nonce is intentionally only a browser-to-loopback anti-CSRF control.
 * Exposing the bridge on a non-loopback interface would make that trust model invalid, so startup
 * fails closed even if {@code server.address} is overridden outside the normal launcher.</p>
 */
@Component
public final class LoopbackBindAddressGuard {
    private final String bindAddress;

    public LoopbackBindAddressGuard(@Value("${server.address:127.0.0.1}") String bindAddress) {
        this.bindAddress = bindAddress == null ? "" : bindAddress.trim();
    }

    @PostConstruct
    public void validate() {
        if (!isLoopbackOnly(bindAddress)) {
            throw new IllegalStateException(
                    "Scanner Bridge must bind only to a loopback address; configured server.address=" + safe(bindAddress));
        }
    }

    static boolean isLoopbackOnly(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            InetAddress[] resolved = InetAddress.getAllByName(value.trim());
            return resolved.length > 0 && Arrays.stream(resolved).allMatch(InetAddress::isLoopbackAddress);
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "<blank>";
        return value.replaceAll("[\\r\\n]", "");
    }
}
