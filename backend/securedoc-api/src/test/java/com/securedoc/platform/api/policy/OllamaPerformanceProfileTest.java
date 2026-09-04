package com.securedoc.platform.api.policy;

import com.securedoc.platform.ai.analysis.OllamaSensitiveContentAnalysisAdapter;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisOutput;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.PageContent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaPerformanceProfileTest {

    private HttpServer server;
    private ExecutorService serverExecutor;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
        if (serverExecutor != null) serverExecutor.shutdownNow();
    }

    @Test
    void batchesPagesAndSendsOptimizedOllamaInferenceControls() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CopyOnWriteArrayList<String> requestBodies = new CopyOnWriteArrayList<>();
        startServer(exchange -> {
            int call = calls.incrementAndGet();
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String content = call == 1
                    ? "{\"findings\":[{\"pageNumber\":2,\"entityType\":\"CREDIT_SCORE\",\"matchedText\":\"718\",\"contextText\":\"FICO score 718\",\"confidence\":0.98}]}"
                    : "{\"findings\":[]}";
            send(exchange, 200, envelope(content));
        });

        OllamaSensitiveContentAnalysisAdapter adapter = new OllamaSensitiveContentAnalysisAdapter(
                "http://localhost:" + server.getAddress().getPort(),
                "gemma3:12b",
                Duration.ofSeconds(5),
                1,
                1,
                Duration.ZERO,
                "30m",
                true,
                false,
                8192,
                768,
                2,
                14_000);

        AnalysisOutput output = adapter.analyze(List.of(
                new PageContent(1, "No sensitive value here"),
                new PageContent(2, "FICO score 718"),
                new PageContent(3, "Another ordinary page"),
                new PageContent(4, "Final ordinary page")));

        assertEquals(2, calls.get(), "four moderate pages should use two provider calls at batch-size two");
        assertEquals(1, output.findings().size());
        assertEquals(2, output.findings().getFirst().pageNumber());

        String request = requestBodies.getFirst();
        assertTrue(request.contains("\"keep_alive\":\"30m\""));
        assertTrue(request.contains("\"think\":false"));
        assertTrue(request.contains("\"num_ctx\":8192"));
        assertTrue(request.contains("\"num_predict\":768"));
        assertTrue(request.contains("\"format\":{"), "structured JSON schema should be sent to Ollama");
        assertTrue(request.contains("<PAGE number=\\\"1\\\">"));
        assertTrue(request.contains("<PAGE number=\\\"2\\\">"));
    }

    @Test
    void rejectsBatchedFindingWhenMatchedTextIsNotOnDeclaredPage() throws Exception {
        startServer(exchange -> send(exchange, 200, envelope(
                "{\"findings\":[{\"pageNumber\":1,\"entityType\":\"CREDIT_SCORE\","
                        + "\"matchedText\":\"718\",\"contextText\":\"FICO score 718\",\"confidence\":0.98}]}")));

        OllamaSensitiveContentAnalysisAdapter adapter = new OllamaSensitiveContentAnalysisAdapter(
                "http://localhost:" + server.getAddress().getPort(), "gemma3:12b", Duration.ofSeconds(5),
                1, 1, Duration.ZERO, "30m", true, false, 8192, 2048, 2, 14_000);

        AnalysisOutput output = adapter.analyze(List.of(
                new PageContent(1, "No score on this page"),
                new PageContent(2, "FICO score 718")));

        assertEquals(0, output.findings().size(), "batched output must be grounded on the declared page");
    }

    @Test
    void oversizedSinglePageIsIsolatedButNotSilentlyTruncated() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CopyOnWriteArrayList<String> requestBodies = new CopyOnWriteArrayList<>();
        startServer(exchange -> {
            calls.incrementAndGet();
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, envelope("{\"findings\":[]}"));
        });

        String oversized = "X".repeat(1200);
        OllamaSensitiveContentAnalysisAdapter adapter = new OllamaSensitiveContentAnalysisAdapter(
                "http://localhost:" + server.getAddress().getPort(), "qwen3.5:4b", Duration.ofSeconds(5),
                1, 1, Duration.ZERO, "30m", true, false, 8192, 2048, 2, 1000);

        adapter.analyze(List.of(new PageContent(1, oversized), new PageContent(2, "small page")));

        assertEquals(2, calls.get(), "oversized page must be isolated instead of forcing truncation or a mixed batch");
        assertTrue(requestBodies.stream().anyMatch(body -> body.contains(oversized)),
                "the oversized single page must be sent intact rather than silently truncated");
    }

    private void startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/api/chat", exchange -> handler.handle(exchange));
        server.start();
    }

    private String envelope(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"message\":{\"content\":\"" + escaped + "\"},"
                + "\"load_duration\":1000000,\"prompt_eval_count\":100,\"prompt_eval_duration\":2000000,"
                + "\"eval_count\":20,\"eval_duration\":1000000,\"done_reason\":\"stop\"}";
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
