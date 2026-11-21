package com.securedoc.platform.scanbridge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.model.PreparedScan;
import com.securedoc.platform.scanbridge.model.ScanSession;
import com.securedoc.platform.scanbridge.model.ScanSessionStatus;
import com.securedoc.platform.scanbridge.model.ScannerCaptureMode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0021: the bridge sends the New case level to LexaVault as caseSensitivityLevel, unchanged. */
class LexaVaultScannerDeliveryClientSensitivityTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode sentBody(String level) throws Exception {
        AtomicReference<byte[]> received = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/scanner-intakes", exchange -> {
            received.set(exchange.getRequestBody().readAllBytes());
            byte[] reply = ("{\"packageId\":\"" + UUID.randomUUID() + "\",\"intakeStatus\":\"AWAITING_DOCUMENTS\","
                    + "\"packageStatus\":\"CREATED\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try {
            ScanBridgeProperties properties = new ScanBridgeProperties();
            properties.setLexavaultBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");
            properties.setTenantId(UUID.randomUUID().toString());
            properties.setLocalActorId(UUID.randomUUID().toString());
            properties.setClientPrincipal("scanner-client");
            Instant now = Instant.parse("2026-11-20T10:00:00Z");
            ScanSession session = new ScanSession(UUID.randomUUID(), UUID.randomUUID(), properties.getTenantId(),
                    "BRIDGE-1", "SITE-1", "SCANNER-1", ScannerCaptureMode.SIMULATOR, ScanSessionStatus.QUEUED,
                    Map.of("contractNumber", "1"), now, now,
                    new PreparedScan(UUID.randomUUID(), "scan.pdf", 10, "a".repeat(64), now, "x/scan.pdf"),
                    null, 0, now, null, null, null, null, level);
            new LexaVaultScannerDeliveryClient(properties, mapper).createIntake(session, false);
            return mapper.readTree(received.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theChosenLevelIsSentAsCaseSensitivityLevel() throws Exception {
        assertEquals("HIGHLY_CONFIDENTIAL", sentBody("HIGHLY_CONFIDENTIAL").get("caseSensitivityLevel").asText());
    }

    @Test
    void noChoiceIsSentAsNothing() throws Exception {
        JsonNode body = sentBody(null);
        assertTrue(!body.has("caseSensitivityLevel") || body.get("caseSensitivityLevel").isNull());
    }
}
