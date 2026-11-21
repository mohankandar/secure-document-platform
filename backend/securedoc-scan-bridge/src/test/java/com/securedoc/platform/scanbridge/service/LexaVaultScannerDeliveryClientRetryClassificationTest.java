package com.securedoc.platform.scanbridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.model.PreparedScan;
import com.securedoc.platform.scanbridge.model.ScanSession;
import com.securedoc.platform.scanbridge.model.ScanSessionStatus;
import com.securedoc.platform.scanbridge.model.ScannerCaptureMode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0023 D2 (#82): which LexaVault answers the bridge retries, and which go straight to a person. */
class LexaVaultScannerDeliveryClientRetryClassificationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private LexaVaultScannerDeliveryClient.DeliveryException refusal(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/scanner-intakes", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] reply = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, reply.length == 0 ? -1 : reply.length);
            if (reply.length > 0) exchange.getResponseBody().write(reply);
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
                    null, 0, now, null, null, null, null, null);
            LexaVaultScannerDeliveryClient client = new LexaVaultScannerDeliveryClient(properties, mapper);
            return assertThrows(LexaVaultScannerDeliveryClient.DeliveryException.class,
                    () -> client.createIntake(session, false));
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 408, 409, 413, 422, 425, 429})
    void every4xxIsTerminalAndKeepsLexaVaultsCode(int status) throws Exception {
        var ex = refusal(status, "{\"errorCode\":\"INTAKE_UNKNOWN_IDENTIFIER\",\"message\":\"Contract 12345 for Jane Doe\"}");
        assertFalse(ex.retryable(), "HTTP " + status + " must not be retried");
        assertEquals("INTAKE_UNKNOWN_IDENTIFIER", ex.safeCode());
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void a5xxIsRetryable(int status) throws Exception {
        var ex = refusal(status, "");
        assertTrue(ex.retryable());
        assertEquals("LEXAVAULT_HTTP_" + status, ex.safeCode());
    }

    @Test
    void anErrorCodeThatIsNotCodeShapedIsReplacedByTheStatusCode() throws Exception {
        var ex = refusal(400, "{\"errorCode\":\"Unknown contract 12345 for Jane Doe\"}");
        assertFalse(ex.retryable());
        assertEquals("LEXAVAULT_HTTP_400", ex.safeCode());
        assertFalse(ex.getMessage().contains("Jane"));
    }
}
