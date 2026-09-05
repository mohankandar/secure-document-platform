package com.securedoc.platform.api.policy;

import com.securedoc.platform.ai.analysis.OpenAiCompatibleSensitiveContentAnalysisAdapter;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisPolicy;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisRequest;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisOutput;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.PageContent;
import com.securedoc.platform.application.resilience.ResilientSensitiveContentAnalysisPort;
import com.securedoc.platform.application.resilience.SelectiveRetryExecutor;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.TenantId;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleAnalysisAdapterTest {

    private HttpServer server;
    private ExecutorService executor;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void callsOpenAiCompatibleEndpointWithTenantPolicyAndDeploymentBearerToken() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"{\\\"findings\\\":[{\\\"entityType\\\":\\\"CREDIT_SCORE\\\",\\\"matchedText\\\":\\\"718\\\",\\\"contextText\\\":\\\"Your credit score 718\\\",\\\"confidence\\\":0.98}]}\"}}]}");
        });

        OpenAiCompatibleSensitiveContentAnalysisAdapter adapter =
                new OpenAiCompatibleSensitiveContentAnalysisAdapter(
                        "http://localhost:" + server.getAddress().getPort(),
                        "Qwen/Qwen3-14B",
                        "deployment-secret",
                        Duration.ofSeconds(5), 1, 1, Duration.ZERO);

        AnalysisOutput output = adapter.analyze(new AnalysisRequest(
                TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                List.of(new PageContent(1, "Your credit score 718")),
                new AnalysisPolicy(Set.of(EntityType.CREDIT_SCORE), true)));

        assertEquals(1, output.findings().size());
        assertEquals("CREDIT_SCORE", output.findings().get(0).entityType());
        assertEquals("Bearer deployment-secret", authorization.get());
        assertTrue(requestBody.get().contains("Qwen/Qwen3-14B"));
        assertTrue(requestBody.get().contains("CREDIT_SCORE"));
        assertFalse(requestBody.get().contains("DATE_OF_BIRTH"));
        assertFalse(requestBody.get().contains("CREDIT_CARD_NUMBER"));
    }


    @Test
    void retriesMalformedProviderContentAtPageScope() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            int requestNumber = requests.incrementAndGet();
            String content = requestNumber == 1 ? "not-json" : "{\"findings\":[]}";
            send(exchange, 200, openAiEnvelope(content));
        });

        OpenAiCompatibleSensitiveContentAnalysisAdapter adapter =
                new OpenAiCompatibleSensitiveContentAnalysisAdapter(
                        "http://localhost:" + server.getAddress().getPort(),
                        "Qwen/Qwen3-14B",
                        "",
                        Duration.ofSeconds(5), 1, 2, Duration.ZERO);

        adapter.analyze(List.of(new PageContent(1, "PAGE_ONE")));

        assertEquals(2, requests.get(), "invalid structured content should be retried at page scope");
    }

    @Test
    void exhaustedMalformedProviderContentDoesNotReplayWholeDocument() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            send(exchange, 200, openAiEnvelope("not-json"));
        });

        OpenAiCompatibleSensitiveContentAnalysisAdapter adapter =
                new OpenAiCompatibleSensitiveContentAnalysisAdapter(
                        "http://localhost:" + server.getAddress().getPort(),
                        "Qwen/Qwen3-14B",
                        "",
                        Duration.ofSeconds(5), 1, 2, Duration.ZERO);
        ResilientSensitiveContentAnalysisPort resilient = new ResilientSensitiveContentAnalysisPort(
                adapter, null, new SelectiveRetryExecutor(2, Duration.ZERO));

        TechnicalDependencyException failure = assertThrows(
                TechnicalDependencyException.class,
                () -> resilient.analyze(List.of(new PageContent(1, "PAGE_ONE"))));

        assertEquals(2, requests.get());
        assertEquals(TechnicalFailureCode.AI_INVALID_RESPONSE, failure.failureCode());
        assertEquals(2, failure.attempts());
        assertFalse(failure.retryable());
    }

    private String openAiEnvelope(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"choices\":[{\"message\":{\"content\":\"" + escaped + "\"}}]}";
    }

    private void startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> handler.handle(exchange));
        server.start();
    }

    private void send(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
