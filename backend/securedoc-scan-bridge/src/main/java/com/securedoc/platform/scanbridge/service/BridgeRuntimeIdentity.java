package com.securedoc.platform.scanbridge.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Non-persistent identity for one Scanner Bridge process.
 *
 * <p>The browser request token is a per-process anti-CSRF/local-browser trust nonce. It is not a
 * LexaVault credential and is rotated automatically on every bridge restart.</p>
 */
@Component
public final class BridgeRuntimeIdentity {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UUID instanceId = UUID.randomUUID();
    private final Instant startedAt = Instant.now();
    private final String browserRequestToken = randomToken();
    private final String applicationVersion = resolveVersion();

    public UUID instanceId() { return instanceId; }
    public Instant startedAt() { return startedAt; }
    public String browserRequestToken() { return browserRequestToken; }
    public String applicationVersion() { return applicationVersion; }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String resolveVersion() {
        String override = System.getProperty("securedoc.scan-bridge.version");
        if (override != null && !override.isBlank()) return override.trim();
        Package pkg = BridgeRuntimeIdentity.class.getPackage();
        String implementation = pkg == null ? null : pkg.getImplementationVersion();
        return implementation == null || implementation.isBlank() ? "0.1.0-SNAPSHOT" : implementation.trim();
    }
}
