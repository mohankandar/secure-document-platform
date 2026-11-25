package com.securedoc.platform.scanbridge.service;

import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.model.BridgeLvCodes;
import com.securedoc.platform.scanbridge.service.LexaVaultScannerDeliveryClient.DeliveryException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Task 0075: the bridge's own sign-in at the customer's login system (OAuth2 client credentials), for a LexaVault that runs
 * with sign-in. The token is kept in memory until shortly before it ends; neither it nor the secret is saved or logged.
 */
final class BridgeSignIn {
    private static final Duration EARLIER = Duration.ofSeconds(30);

    private final ScanBridgeProperties.Oidc settings;
    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final Clock clock;
    private String token;
    private Instant until = Instant.MIN;

    BridgeSignIn(ScanBridgeProperties.Oidc settings, ObjectMapper objectMapper, HttpClient http, Clock clock) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.http = http;
        this.clock = clock;
    }

    /** One of the settings is there, so the bridge signs in (or refuses to send, when they are not complete). */
    boolean configured() {
        return !blank(settings.getTokenUri()) || !blank(settings.getClientId()) || !blank(settings.getClientSecret());
    }

    synchronized void forget() {
        token = null;
    }

    synchronized String token() {
        if (token != null && clock.instant().isBefore(until)) return token;
        token = null;
        if (blank(settings.getTokenUri()) || blank(settings.getClientId()) || blank(settings.getClientSecret())) {
            throw DeliveryException.terminal("LEXAVAULT_ENDPOINT_NOT_CONFIGURED", null);
        }
        URI uri = secure(settings.getTokenUri().trim());
        String form = "grant_type=client_credentials&client_id=" + encode(settings.getClientId().trim())
                + "&client_secret=" + encode(settings.getClientSecret())
                + (blank(settings.getScope()) ? "" : "&scope=" + encode(settings.getScope().trim()));
        HttpResponse<byte[]> answer;
        try {
            answer = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw DeliveryException.retryable("LEXAVAULT_REQUEST_INTERRUPTED", null);
        } catch (IOException ex) {
            throw DeliveryException.retryable("LEXAVAULT_UNAVAILABLE", null);
        }
        int status = answer.statusCode();
        if (status != 200) {
            String code = BridgeLvCodes.LEXAVAULT_HTTP_PREFIX + status;
            if (LexaVaultScannerDeliveryClient.retryableStatus(status)) throw DeliveryException.retryable(code, null, null, status, null);
            throw DeliveryException.terminal(code, null, status, null);
        }
        String received;
        long seconds;
        try {
            JsonNode json = objectMapper.readTree(answer.body());
            received = json.path("access_token").asString("");
            seconds = json.path("expires_in").asLong(0);
        } catch (RuntimeException unreadable) {
            throw DeliveryException.retryable("LEXAVAULT_INVALID_RESPONSE", null);
        }
        if (blank(received)) throw DeliveryException.retryable("LEXAVAULT_INVALID_RESPONSE", null);
        token = received;
        until = clock.instant().plusSeconds(seconds).minus(EARLIER);
        return token;
    }

    /** The secret goes over https only, or to this machine. */
    private static URI secure(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())
                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()))) return uri;
        } catch (IllegalArgumentException invalid) {
            // Refused below; the value is not repeated.
        }
        throw DeliveryException.terminal("LEXAVAULT_ENDPOINT_NOT_CONFIGURED", null);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
