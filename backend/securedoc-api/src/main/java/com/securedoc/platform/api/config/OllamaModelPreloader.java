package com.securedoc.platform.api.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Preloads the deployment-default Ollama model without sending customer content.
 *
 * <p>For a configured HA inference pool, each node is warmed independently. Startup fail-closed
 * means at least one pool node must successfully load the exact configured model; a single failed
 * pool member does not take the whole application down when another healthy member is ready.
 * Tenant-specific profiles remain lazy to avoid loading several large models at application startup.</p>
 */
@Component
@ConditionalOnProperty(prefix = "securedoc.ai", name = "ollama-preload-enabled", havingValue = "true")
public final class OllamaModelPreloader {

    private static final Logger log = LoggerFactory.getLogger(OllamaModelPreloader.class);

    private final AiRuntimeProperties properties;
    private final ObjectMapper objectMapper;

    public OllamaModelPreloader(AiRuntimeProperties properties, ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @PostConstruct
    void preload() {
        if (!"ollama".equalsIgnoreCase(properties.getProvider())) {
            log.info("AI_MODEL_PRELOAD_SKIPPED reason=provider_not_ollama provider={}", safeProvider(properties.getProvider()));
            return;
        }

        String model = requireNonBlank(properties.getModel(), "Ollama model");
        int timeoutSeconds = properties.getOllamaPreloadTimeoutSeconds();
        if (timeoutSeconds < 1 || timeoutSeconds > 1800) {
            throw new IllegalArgumentException("Ollama preload timeout must be between 1 and 1800 seconds");
        }

        List<OllamaInferencePoolConfiguration.NodeTarget> targets = resolveTargets();
        int succeeded = 0;
        int failed = 0;
        Exception lastFailure = null;

        for (OllamaInferencePoolConfiguration.NodeTarget target : targets) {
            try {
                preloadTarget(target, model, timeoutSeconds);
                succeeded++;
            } catch (Exception e) {
                failed++;
                lastFailure = e;
                log.warn("AI_MODEL_PRELOAD_NODE_FAILED provider=ollama model={} inferenceNodeId={} reason={}",
                        model, target.id(), safeReason(e));
            }
        }

        if (succeeded == 0 && properties.isOllamaPreloadFailOnError()) {
            String reason = lastFailure == null ? "NO_READY_NODE" : safeReason(lastFailure);
            log.error("AI_MODEL_PRELOAD_FAILED provider=ollama model={} configuredNodes={} readyNodes=0 reason={} failClosed=true",
                    model, targets.size(), reason);
            throw new IllegalStateException("Configured Ollama model preload failed on every inference node: " + reason, lastFailure);
        }

        if (succeeded == 0) {
            log.warn("AI_MODEL_PRELOAD_FAILED provider=ollama model={} configuredNodes={} readyNodes=0 failClosed=false",
                    model, targets.size());
        } else {
            log.info("AI_MODEL_PRELOAD_POOL_READY provider=ollama model={} configuredNodes={} readyNodes={} failedNodes={}",
                    model, targets.size(), succeeded, failed);
        }
    }

    private List<OllamaInferencePoolConfiguration.NodeTarget> resolveTargets() {
        if (properties.isPoolEnabled()) {
            return OllamaInferencePoolConfiguration.parse(properties.getPoolNodeUrls());
        }
        return List.of(new OllamaInferencePoolConfiguration.NodeTarget(
                "default", requireNonBlank(properties.getBaseUrl(), "Ollama base URL")));
    }

    private void preloadTarget(OllamaInferencePoolConfiguration.NodeTarget target,
                               String model,
                               int timeoutSeconds) throws Exception {
        long startedNanos = System.nanoTime();
        URI endpoint = URI.create(stripTrailingSlash(target.baseUrl()) + "/api/generate");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("prompt", "");
        payload.put("stream", false);
        payload.put("keep_alive", normalizedKeepAlive(properties.getOllamaKeepAlive()));
        if (properties.getOllamaContextWindowTokens() > 0) {
            payload.put("options", Map.of("num_ctx", properties.getOllamaContextWindowTokens()));
        }

        String body = objectMapper.writeValueAsString(payload);
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        log.info("AI_MODEL_PRELOAD_STARTED provider=ollama model={} inferenceNodeId={} keepAlive={} numCtx={} timeoutSeconds={}",
                model, target.id(), normalizedKeepAlive(properties.getOllamaKeepAlive()),
                properties.getOllamaContextWindowTokens(), timeoutSeconds);

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new PreloadException("HTTP_" + response.statusCode());
        }

        long providerLoadMs = parseProviderLoadMs(response.body());
        long durationMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        log.info("AI_MODEL_PRELOAD_SUCCEEDED provider=ollama model={} inferenceNodeId={} durationMs={} providerLoadMs={} keepAlive={} numCtx={}",
                model, target.id(), durationMs, providerLoadMs, normalizedKeepAlive(properties.getOllamaKeepAlive()),
                properties.getOllamaContextWindowTokens());
    }

    private long parseProviderLoadMs(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return 0L;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            long loadNanos = root.path("load_duration").asLong(0L);
            return loadNanos <= 0L ? 0L : Duration.ofNanos(loadNanos).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String stripTrailingSlash(String value) {
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String normalizedKeepAlive(String value) {
        return value == null || value.isBlank() ? "5m" : value.trim();
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }

    private static String safeProvider(String value) {
        return value == null || value.isBlank() ? "blank" : value.trim();
    }

    private static String safeReason(Exception e) {
        if (e instanceof PreloadException preloadException) {
            return preloadException.safeCode;
        }
        return e.getClass().getSimpleName();
    }

    private static final class PreloadException extends RuntimeException {
        private final String safeCode;

        private PreloadException(String safeCode) {
            super(safeCode);
            this.safeCode = safeCode;
        }
    }
}
