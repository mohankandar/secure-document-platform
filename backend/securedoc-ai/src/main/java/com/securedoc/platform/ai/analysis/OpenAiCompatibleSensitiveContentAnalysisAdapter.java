package com.securedoc.platform.ai.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provider adapter for OpenAI-compatible chat-completions endpoints, including vLLM and
 * customer-managed compatible gateways/models. Credentials are supplied by deployment
 * configuration and are never logged.
 */
public final class OpenAiCompatibleSensitiveContentAnalysisAdapter implements SensitiveContentAnalysisPort {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleSensitiveContentAnalysisAdapter.class);
    private static final String VERSION = "1.1.0";
    private static final String PROVIDER = "openai-compatible";

    private final URI chatEndpoint;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final int configuredParallelism;
    private final int pageMaxAttempts;
    private final Duration pageRetryBackoff;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SensitiveContentPromptBuilder promptBuilder;
    private final SensitiveContentResponseParser responseParser;

    public OpenAiCompatibleSensitiveContentAnalysisAdapter(String baseUrl,
                                                            String model,
                                                            String apiKey,
                                                            Duration timeout,
                                                            int parallelism,
                                                            int pageMaxAttempts,
                                                            Duration pageRetryBackoff) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("OpenAI-compatible base URL must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("OpenAI-compatible model must not be blank");
        }
        if (parallelism < 1 || parallelism > 16) {
            throw new IllegalArgumentException("AI parallelism must be between 1 and 16");
        }
        if (pageMaxAttempts < 1 || pageMaxAttempts > 5) {
            throw new IllegalArgumentException("AI pageMaxAttempts must be between 1 and 5");
        }
        this.chatEndpoint = resolveChatEndpoint(baseUrl);
        this.model = model.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        this.configuredParallelism = parallelism;
        this.pageMaxAttempts = pageMaxAttempts;
        this.pageRetryBackoff = pageRetryBackoff == null ? Duration.ZERO : pageRetryBackoff;
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.objectMapper = new ObjectMapper();
        this.promptBuilder = new SensitiveContentPromptBuilder();
        this.responseParser = new SensitiveContentResponseParser(this.objectMapper);
    }

    @Override
    public AnalysisOutput analyze(AnalysisRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        List<PageContent> nonBlankPages = request.pages().stream()
                .filter(page -> !page.text().isBlank())
                .toList();
        if (nonBlankPages.isEmpty()) {
            return new AnalysisOutput(List.of());
        }

        int parallelism = Math.min(configuredParallelism, nonBlankPages.size());
        AtomicInteger pageRetryCount = new AtomicInteger();
        String systemPrompt = promptBuilder.build(request.policy());
        long startedNanos = System.nanoTime();
        log.info("AI_ANALYSIS_STARTED provider={} model={} pages={} parallelism={} timeoutSeconds={} pageMaxAttempts={}",
                PROVIDER, model, nonBlankPages.size(), parallelism, timeout.toSeconds(), pageMaxAttempts);

        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<List<DiscoveredSensitiveContent>>> futures = new ArrayList<>(nonBlankPages.size());
            for (PageContent page : nonBlankPages) {
                futures.add(executor.submit(() -> analyzePageWithRetry(page, pageRetryCount, systemPrompt)));
            }

            List<DiscoveredSensitiveContent> discoveries = new ArrayList<>();
            for (Future<List<DiscoveredSensitiveContent>> future : futures) {
                try {
                    discoveries.addAll(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("AI provider analysis was interrupted", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new IllegalStateException("AI provider page analysis failed", cause);
                }
            }
            log.info("AI_ANALYSIS_COMPLETED provider={} model={} pages={} findings={} pageRetries={} durationMs={}",
                    PROVIDER, model, nonBlankPages.size(), discoveries.size(), pageRetryCount.get(), elapsedMillis(startedNanos));
            return new AnalysisOutput(discoveries);
        } catch (RuntimeException e) {
            String reason = e instanceof TechnicalDependencyException technical
                    ? technical.failureCode().name()
                    : e.getClass().getSimpleName();
            int attempts = e instanceof TechnicalDependencyException technical
                    ? technical.attempts()
                    : 1;
            log.warn("AI_ANALYSIS_FAILED provider={} model={} pages={} reason={} attempts={} pageRetries={} durationMs={}",
                    PROVIDER, model, nonBlankPages.size(), reason, attempts, pageRetryCount.get(), elapsedMillis(startedNanos));
            throw e;
        } finally {
            executor.shutdownNow();
        }
    }

    private List<DiscoveredSensitiveContent> analyzePageWithRetry(PageContent page,
                                                                   AtomicInteger pageRetryCount,
                                                                   String systemPrompt) {
        int attempt = 0;
        long pageStartedNanos = System.nanoTime();
        while (true) {
            attempt++;
            long attemptStartedNanos = System.nanoTime();
            try {
                List<DiscoveredSensitiveContent> result = analyzePage(page, systemPrompt);
                if (attempt > 1) {
                    log.info("AI_PAGE_RECOVERED provider={} model={} page={} attempts={} durationMs={}",
                            PROVIDER, model, page.pageNumber(), attempt, elapsedMillis(pageStartedNanos));
                }
                return result;
            } catch (TechnicalDependencyException e) {
                boolean retrying = e.retryable() && attempt < pageMaxAttempts;
                log.warn("AI_PAGE_ATTEMPT_FAILED provider={} model={} page={} attempt={} maxAttempts={} reason={} retrying={} durationMs={}",
                        PROVIDER, model, page.pageNumber(), attempt, pageMaxAttempts, e.failureCode(), retrying,
                        elapsedMillis(attemptStartedNanos));
                if (!retrying) {
                    if (e.retryable()) {
                        throw exhaustedPageFailure(e, attempt);
                    }
                    throw e.withAttempts(attempt);
                }
                pageRetryCount.incrementAndGet();
                sleepPageBackoff(attempt);
            }
        }
    }

    private List<DiscoveredSensitiveContent> analyzePage(PageContent page, String systemPrompt) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("temperature", 0);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", "Page " + page.pageNumber() + " text:\n" + page.text())
        ));

        final String body;
        try {
            body = objectMapper.writeValueAsString(requestBody);
        } catch (IOException e) {
            throw technical(TechnicalFailureCode.AI_INVALID_RESPONSE, false,
                    "AI request could not be prepared", e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(chatEndpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json");
        if (!apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpRequest httpRequest = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();

        final HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw technical(TechnicalFailureCode.AI_TIMEOUT, true, "AI provider timed out", e);
        } catch (ConnectException e) {
            throw technical(TechnicalFailureCode.AI_UNAVAILABLE, true, "AI provider is unavailable", e);
        } catch (IOException e) {
            throw technical(TechnicalFailureCode.AI_UNAVAILABLE, true, "AI provider request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw technical(TechnicalFailureCode.AI_UNAVAILABLE, false, "AI provider analysis was interrupted", e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            if (status == 429) {
                throw technical(TechnicalFailureCode.AI_RATE_LIMITED, true, "AI provider is rate limited", null);
            }
            if (status == 500 || status == 502 || status == 503 || status == 504) {
                throw technical(TechnicalFailureCode.AI_UNAVAILABLE, true, "AI provider is temporarily unavailable", null);
            }
            throw technical(TechnicalFailureCode.AI_REJECTED, false, "AI provider rejected the request", null);
        }

        try {
            JsonNode envelope = objectMapper.readTree(response.body());
            JsonNode choices = envelope.path("choices");
            String content = choices.isArray() && choices.size() > 0
                    ? choices.get(0).path("message").path("content").asText(null)
                    : null;
            if (content == null || content.isBlank()) {
                throw technical(TechnicalFailureCode.AI_INVALID_RESPONSE, true,
                        "AI provider returned no analysis content", null);
            }
            return responseParser.parse(content, page, PROVIDER, model,
                    PROVIDER + ":" + model, VERSION);
        } catch (TechnicalDependencyException e) {
            throw e;
        } catch (IOException e) {
            throw technical(TechnicalFailureCode.AI_INVALID_RESPONSE, true,
                    "AI provider response could not be processed", e);
        }
    }

    private TechnicalDependencyException exhaustedPageFailure(TechnicalDependencyException failure, int attempts) {
        return new TechnicalDependencyException(
                failure.dependency(), failure.failureCode(), false,
                failure.getMessage(), failure).withAttempts(attempts);
    }

    private void sleepPageBackoff(int completedAttempt) {
        long baseMillis = Math.max(0L, pageRetryBackoff.toMillis());
        long sleepMillis = Math.min(5_000L, baseMillis * Math.max(1, completedAttempt));
        if (sleepMillis <= 0L) {
            return;
        }
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw technical(TechnicalFailureCode.AI_UNAVAILABLE, false,
                    "AI provider retry was interrupted", e);
        }
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private TechnicalDependencyException technical(TechnicalFailureCode code,
                                                    boolean retryable,
                                                    String safeMessage,
                                                    Throwable cause) {
        return new TechnicalDependencyException(TechnicalDependency.AI, code, retryable, safeMessage, cause);
    }

    static URI resolveChatEndpoint(String baseUrl) {
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        if (normalized.endsWith("/chat/completions")) {
            return URI.create(normalized);
        }
        if (normalized.endsWith("/v1")) {
            return URI.create(normalized + "/chat/completions");
        }
        return URI.create(normalized + "/v1/chat/completions");
    }
}
