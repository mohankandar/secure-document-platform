package com.securedoc.platform.api.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaModelPreloaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preloadUsesEmptyPromptSelectedModelKeepAliveAndContextWithoutCustomerContent() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"done\":true,\"load_duration\":250000000}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AiRuntimeProperties properties = properties(server.getAddress().getPort());
            new OllamaModelPreloader(properties, objectMapper).preload();

            JsonNode body = objectMapper.readTree(requestBody.get());
            assertEquals("qwen3.5:4b", body.path("model").asText());
            assertEquals("", body.path("prompt").asText());
            assertFalse(body.path("stream").asBoolean(true));
            assertEquals("30m", body.path("keep_alive").asText());
            assertEquals(8192, body.path("options").path("num_ctx").asInt());
            assertEquals(5, body.size(), "warmup payload must contain only model-loading controls");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preloadFailsClosedWithoutSwitchingModelWhenOllamaRejectsSelectedModel() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            byte[] response = "{\"error\":\"model unavailable\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AiRuntimeProperties properties = properties(server.getAddress().getPort());
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new OllamaModelPreloader(properties, objectMapper).preload());
            assertTrue(failure.getMessage().contains("HTTP_404"));
            assertEquals("qwen3.5:4b", properties.getModel(), "preload failure must not mutate/switch the configured model");
        } finally {
            server.stop(0);
        }
    }

    private AiRuntimeProperties properties(int port) {
        AiRuntimeProperties properties = new AiRuntimeProperties();
        properties.setProvider("ollama");
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setModel("qwen3.5:4b");
        properties.setOllamaKeepAlive("30m");
        properties.setOllamaContextWindowTokens(8192);
        properties.setOllamaPreloadEnabled(true);
        properties.setOllamaPreloadFailOnError(true);
        properties.setOllamaPreloadTimeoutSeconds(5);
        return properties;
    }
}
