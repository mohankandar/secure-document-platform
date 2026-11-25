package com.securedoc.platform.scanbridge.service;

import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.service.LexaVaultScannerDeliveryClient.DeliveryException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0075: the bridge's own sign-in, against a small server on this machine. */
class BridgeSignInTest {
    private final List<String> tokenRequests = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();
    private final List<String> entities = new ArrayList<>();
    private HttpServer server;
    private int tokenStatus = 200;
    private int apiStatus = 200;
    private int seconds = 300;
    private ScanBridgeProperties properties;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            tokenRequests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"access_token\":\"t" + tokenRequests.size() + "\",\"expires_in\":" + seconds + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(tokenStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v1/scanner-intakes", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            entities.add(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
            byte[] body = ("{\"packageId\":\"" + UUID.randomUUID() + "\",\"intakeStatus\":\"AWAITING_DOCUMENTS\",\"packageStatus\":\"CREATED\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(apiStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        properties = new ScanBridgeProperties();
        properties.setLexavaultBaseUrl(base + "/api/v1");
        properties.getOidc().setTokenUri(base + "/token");
        properties.getOidc().setClientId("scan bridge");
        properties.getOidc().setClientSecret("not&a=secret");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private LexaVaultScannerDeliveryClient client() {
        return new LexaVaultScannerDeliveryClient(properties, JsonMapper.builderWithJackson2Defaults().build());
    }

    @Test
    void aTokenIsFetchedOnceAndUsedUntilItsEndAndNoEntityHeaderIsSent() {
        LexaVaultScannerDeliveryClient client = client();
        client.status(UUID.randomUUID());
        client.status(UUID.randomUUID());
        assertEquals(List.of("grant_type=client_credentials&client_id=scan+bridge&client_secret=not%26a%3Dsecret"), tokenRequests);
        assertEquals(List.of("Bearer t1", "Bearer t1"), authorizations);
        assertTrue(entities.stream().allMatch(java.util.Objects::isNull));
    }

    @Test
    void aTokenThatEndsSoonIsFetchedAgain() {
        seconds = 10;
        LexaVaultScannerDeliveryClient client = client();
        client.status(UUID.randomUUID());
        client.status(UUID.randomUUID());
        assertEquals(List.of("Bearer t1", "Bearer t2"), authorizations);
    }

    @Test
    void aRefusedTokenIsDroppedAndTriedAgainLater() {
        LexaVaultScannerDeliveryClient client = client();
        apiStatus = 401;
        DeliveryException refused = assertThrows(DeliveryException.class, () -> client.status(UUID.randomUUID()));
        assertTrue(refused.retryable());
        apiStatus = 200;
        client.status(UUID.randomUUID());
        assertEquals(List.of("Bearer t1", "Bearer t2"), authorizations);
    }

    @Test
    void theLoginSystemsRefusalNeedsAPersonItsOutageIsTriedAgainAndNothingIsSent() {
        tokenStatus = 401;
        DeliveryException refused = assertThrows(DeliveryException.class, () -> client().status(UUID.randomUUID()));
        assertFalse(refused.retryable());
        assertEquals("LEXAVAULT_HTTP_401", refused.safeCode());
        tokenStatus = 503;
        assertTrue(assertThrows(DeliveryException.class, () -> client().status(UUID.randomUUID())).retryable());
        assertTrue(authorizations.isEmpty());
    }

    @Test
    void incompleteOrUnsafeSettingsSendNothing() {
        for (String uri : new String[] {"http://login.example/token", "ftp://127.0.0.1/token", "not an address", ""}) {
            properties.getOidc().setTokenUri(uri);
            DeliveryException refused = assertThrows(DeliveryException.class, () -> client().status(UUID.randomUUID()), uri);
            assertEquals("LEXAVAULT_ENDPOINT_NOT_CONFIGURED", refused.safeCode());
            assertFalse(refused.retryable());
        }
        assertTrue(tokenRequests.isEmpty());
        assertTrue(authorizations.isEmpty(), "never with the local headers instead");
    }
}
