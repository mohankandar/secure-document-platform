package com.securedoc.platform.api.policy;

import com.securedoc.platform.ai.analysis.OllamaSensitiveContentAnalysisAdapter;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.PageContent;
import com.securedoc.platform.application.resilience.ResilientSensitiveContentAnalysisPort;
import com.securedoc.platform.application.resilience.SelectiveRetryExecutor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OllamaPageRetryTest {

    private HttpServer server;
    private ExecutorService serverExecutor;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void retriesOnlyTheTransientlyFailedPage() throws Exception {
        ConcurrentHashMap<Integer, AtomicInteger> requestsByPage = new ConcurrentHashMap<>();
        startServer(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int page = body.contains("PAGE_TWO") ? 2 : 1;
            int requestNumber = requestsByPage.computeIfAbsent(page, ignored -> new AtomicInteger()).incrementAndGet();

            if (page == 2 && requestNumber == 1) {
                send(exchange, 503, "{}");
                return;
            }
            sendSuccess(exchange);
        });

        OllamaSensitiveContentAnalysisAdapter adapter = adapter(2, 2);
        adapter.analyze(List.of(
                new PageContent(1, "PAGE_ONE"),
                new PageContent(2, "PAGE_TWO")));

        assertEquals(1, requestsByPage.get(1).get(), "successful page must not be replayed");
        assertEquals(2, requestsByPage.get(2).get(), "only failed page should be retried");
    }

    @Test
    void exhaustedPageRetryIsMarkedNonRetryableForOuterDocumentDecorator() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            send(exchange, 503, "{}");
        });

        OllamaSensitiveContentAnalysisAdapter adapter = adapter(1, 2);
        ResilientSensitiveContentAnalysisPort resilient = new ResilientSensitiveContentAnalysisPort(
                adapter, null, new SelectiveRetryExecutor(2, Duration.ZERO));

        TechnicalDependencyException failure = assertThrows(
                TechnicalDependencyException.class,
                () -> resilient.analyze(List.of(new PageContent(1, "PAGE_ONE"))));

        assertEquals(2, requests.get());
        assertEquals(TechnicalFailureCode.AI_UNAVAILABLE, failure.failureCode());
        assertEquals(2, failure.attempts());
        assertFalse(failure.retryable(), "outer retry must not restart the whole document");
    }

    private OllamaSensitiveContentAnalysisAdapter adapter(int parallelism, int pageMaxAttempts) {
        return new OllamaSensitiveContentAnalysisAdapter(
                "http://localhost:" + server.getAddress().getPort(),
                "test-model",
                Duration.ofSeconds(5),
                parallelism,
                pageMaxAttempts,
                Duration.ZERO);
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/api/chat", exchange -> handler.handle(exchange));
        server.start();
    }

    private void sendSuccess(HttpExchange exchange) throws IOException {
        send(exchange, 200, "{\"message\":{\"content\":\"{\\\"findings\\\":[]}\"}}");
    }

    private void send(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
