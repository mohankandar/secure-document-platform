package com.securedoc.platform.scanbridge.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0023 D2 (#82): which LexaVault answers the bridge retries, and which go straight to a person. */
class LexaVaultScannerDeliveryClientRetryClassificationTest {
    private final ObjectMapper mapper = JsonMapper.builderWithJackson2Defaults().build();

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private LexaVaultScannerDeliveryClient.DeliveryException refusal(int status, String body) throws Exception {
        return refusal(status, body, null);
    }

    private LexaVaultScannerDeliveryClient.DeliveryException refusal(int status, String body, String retryAfter) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/scanner-intakes", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] reply = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (retryAfter != null) exchange.getResponseHeaders().add("Retry-After", retryAfter);
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
                    null, 0, now, null, null, null, null, null, null);
            LexaVaultScannerDeliveryClient client = new LexaVaultScannerDeliveryClient(properties, mapper,
                    Clock.fixed(NOW, ZoneOffset.UTC));
            return assertThrows(LexaVaultScannerDeliveryClient.DeliveryException.class,
                    () -> client.createIntake(session, false));
        } finally {
            server.stop(0);
        }
    }

    /** Task 0024: 429 is no longer terminal; it is the one 4xx that is retried. */
    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 408, 409, 413, 422, 425})
    void every4xxOtherThan429IsTerminalAndKeepsLexaVaultsCode(int status) throws Exception {
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
    void a429WithRetryAfterInSecondsIsRetryableWithThatWait() throws Exception {
        var ex = refusal(429, "{\"errorCode\":\"RATE_LIMITED\"}", "120");
        assertTrue(ex.retryable());
        assertEquals("RATE_LIMITED", ex.safeCode());
        assertEquals(Duration.ofSeconds(120), ex.retryAfter());
    }

    @Test
    void a429WithRetryAfterAsADateIsTheTimeUntilThatDate() throws Exception {
        var ex = refusal(429, "", "Fri, 20 Nov 2026 10:01:30 GMT");
        assertTrue(ex.retryable());
        assertEquals("LEXAVAULT_HTTP_429", ex.safeCode());
        assertEquals(Duration.ofSeconds(90), ex.retryAfter());
    }

    @Test
    void a429WithoutRetryAfterIsRetryableWithNoWaitGiven() throws Exception {
        var ex = refusal(429, "");
        assertTrue(ex.retryable());
        assertNull(ex.retryAfter());
    }

    @Test
    void retryAfterParsing() {
        assertEquals(Duration.ZERO, LexaVaultScannerDeliveryClient.retryAfter("Fri, 20 Nov 2026 09:00:00 GMT", NOW),
                "a date in the past is no wait");
        assertNull(LexaVaultScannerDeliveryClient.retryAfter("soon", NOW));
        assertNull(LexaVaultScannerDeliveryClient.retryAfter("-5", NOW));
        assertNull(LexaVaultScannerDeliveryClient.retryAfter(" ", NOW));
        assertEquals(Duration.ofSeconds(Long.MAX_VALUE),
                LexaVaultScannerDeliveryClient.retryAfter("99999999999999999999999", NOW), "huge is bounded later");
    }

    @Test
    void retryAfterIsIgnoredOnA503() throws Exception {
        var ex = refusal(503, "", "30");
        assertTrue(ex.retryable());
        assertNull(ex.retryAfter(), "the owner's rule names 429 only");
    }

    /** Task 0025: LexaVault's LV code is kept beside its errorCode as the detail; its message is never kept. */
    @Test
    void aRefusalKeepsLexaVaultsLvCodeAndStatus() throws Exception {
        var ex = refusal(400, "{\"errorCode\":\"INTAKE_UNKNOWN_IDENTIFIER\",\"code\":\"LV-INT-014\","
                + "\"message\":\"Contract 12345 for Jane Doe\"}");
        assertFalse(ex.retryable());
        assertEquals("INTAKE_UNKNOWN_IDENTIFIER", ex.safeCode());
        assertEquals("LV-INT-014", ex.lexaVaultLvCode());
        assertEquals(400, ex.httpStatus());
        assertEquals(com.securedoc.platform.scanbridge.model.BridgeLvCode.LV_SCN_016,
                com.securedoc.platform.scanbridge.model.BridgeLvCodes.forDelivery(ex.safeCode(), ex.httpStatus(),
                        ScanSessionStatus.ATTENTION_REQUIRED));
        assertFalse(ex.getMessage().contains("Jane"));
    }

    @Test
    void aLexaVaultCodeThatIsNotLvShapedIsDropped() throws Exception {
        var ex = refusal(409, "{\"errorCode\":\"VALIDATION_ERROR\",\"code\":\"Contract 12345 for Jane Doe\"}");
        assertEquals("VALIDATION_ERROR", ex.safeCode());
        assertNull(ex.lexaVaultLvCode());
        assertEquals(409, ex.httpStatus());
    }

    @Test
    void a5xxKeepsItsStatusAndAnyLvCode() throws Exception {
        var ex = refusal(503, "{\"errorCode\":\"LICENSE_UNAVAILABLE\",\"code\":\"LV-LIC-004\"}");
        assertTrue(ex.retryable());
        assertEquals(503, ex.httpStatus());
        assertEquals("LV-LIC-004", ex.lexaVaultLvCode());
    }

    /** Task 0025: the LV mapping's idea of a transient LexaVault answer is the client's retry rule. */
    @Test
    void theLvMappingAndTheRetryRuleAgreeOnEveryStatus() {
        for (int status = 100; status <= 599; status++) {
            assertEquals(LexaVaultScannerDeliveryClient.retryableStatus(status),
                    com.securedoc.platform.scanbridge.model.BridgeLvCodes.lexaVaultAnswerIsTransient(status), "HTTP " + status);
        }
    }

    @Test
    void anErrorCodeThatIsNotCodeShapedIsReplacedByTheStatusCode() throws Exception {
        var ex = refusal(400, "{\"errorCode\":\"Unknown contract 12345 for Jane Doe\"}");
        assertFalse(ex.retryable());
        assertEquals("LEXAVAULT_HTTP_400", ex.safeCode());
        assertFalse(ex.getMessage().contains("Jane"));
    }
}
