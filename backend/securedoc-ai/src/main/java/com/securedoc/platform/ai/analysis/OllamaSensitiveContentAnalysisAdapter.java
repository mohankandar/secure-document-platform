package com.securedoc.platform.ai.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
import com.securedoc.platform.application.observability.StructuredLogContext;

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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local/on-prem AI adapter backed by Ollama.
 *
 * <p>Requirement 5 keeps semantic discovery AI-first while reducing local inference overhead:
 * bounded page batching reduces repeated prompt/model calls, JSON-schema structured output constrains
 * completion shape, optional token/context budgets avoid waste, thinking is disabled for extraction,
 * and keep-alive keeps the configured model resident between requests. All knobs remain deployment
 * configuration; provider/model selection is not hard-coded into application/domain logic.
 */
public final class OllamaSensitiveContentAnalysisAdapter implements SensitiveContentAnalysisPort {

    private static final Logger log = LoggerFactory.getLogger(OllamaSensitiveContentAnalysisAdapter.class);
    private static final String VERSION = "1.2.0";
    private static final int MAX_BATCH_SIZE = 8;

    private final URI chatEndpoint;
    private final String model;
    private final Duration timeout;
    private final int configuredParallelism;
    private final int pageMaxAttempts;
    private final Duration pageRetryBackoff;
    private final String keepAlive;
    private final boolean structuredOutput;
    private final boolean thinkingEnabled;
    private final int contextWindowTokens;
    private final int maxOutputTokens;
    private final int pageBatchSize;
    private final int pageBatchMaxCharacters;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SensitiveContentPromptBuilder promptBuilder;
    private final SensitiveContentResponseParser responseParser;

    public OllamaSensitiveContentAnalysisAdapter(String baseUrl, String model, Duration timeout) {
        this(baseUrl, model, timeout, 2, 2, Duration.ofMillis(500));
    }

    /** Backward-compatible constructor used by existing tests and non-tuned callers. */
    public OllamaSensitiveContentAnalysisAdapter(String baseUrl,
                                                  String model,
                                                  Duration timeout,
                                                  int parallelism,
                                                  int pageMaxAttempts,
                                                  Duration pageRetryBackoff) {
        this(baseUrl, model, timeout, parallelism, pageMaxAttempts, pageRetryBackoff,
                "5m", true, false, 0, 0, 1, 14_000);
    }

    public OllamaSensitiveContentAnalysisAdapter(String baseUrl,
                                                  String model,
                                                  Duration timeout,
                                                  int parallelism,
                                                  int pageMaxAttempts,
                                                  Duration pageRetryBackoff,
                                                  String keepAlive,
                                                  boolean structuredOutput,
                                                  boolean thinkingEnabled,
                                                  int contextWindowTokens,
                                                  int maxOutputTokens,
                                                  int pageBatchSize,
                                                  int pageBatchMaxCharacters) {
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Ollama model must not be blank");
        }
        if (parallelism < 1 || parallelism > 16) {
            throw new IllegalArgumentException("Ollama parallelism must be between 1 and 16");
        }
        if (pageMaxAttempts < 1 || pageMaxAttempts > 5) {
            throw new IllegalArgumentException("Ollama pageMaxAttempts must be between 1 and 5");
        }
        if (pageBatchSize < 1 || pageBatchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Ollama pageBatchSize must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (pageBatchMaxCharacters < 1_000 || pageBatchMaxCharacters > 200_000) {
            throw new IllegalArgumentException("Ollama pageBatchMaxCharacters must be between 1000 and 200000");
        }
        if (contextWindowTokens < 0 || maxOutputTokens < 0) {
            throw new IllegalArgumentException("Ollama token limits must be zero (provider default) or positive");
        }
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.chatEndpoint = URI.create(normalized + "/api/chat");
        this.model = model.trim();
        this.timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        this.configuredParallelism = parallelism;
        this.pageMaxAttempts = pageMaxAttempts;
        this.pageRetryBackoff = pageRetryBackoff == null ? Duration.ZERO : pageRetryBackoff;
        this.keepAlive = keepAlive == null || keepAlive.isBlank() ? "5m" : keepAlive.trim();
        this.structuredOutput = structuredOutput;
        this.thinkingEnabled = thinkingEnabled;
        this.contextWindowTokens = contextWindowTokens;
        this.maxOutputTokens = maxOutputTokens;
        this.pageBatchSize = pageBatchSize;
        this.pageBatchMaxCharacters = pageBatchMaxCharacters;
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

        List<List<PageContent>> batches = buildBatches(nonBlankPages);
        int parallelism = Math.min(configuredParallelism, batches.size());
        AtomicInteger pageRetryCount = new AtomicInteger();
        AtomicInteger providerCallCount = new AtomicInteger();
        String systemPrompt = promptBuilder.buildCompact(request.policy());
        JsonNode responseSchema = structuredOutput ? buildResponseSchema() : null;
        long startedNanos = System.nanoTime();

        StructuredLogContext.info(log, "AI_ANALYSIS_STARTED", Map.of(
                "provider", "ollama", "model", model, "pageCount", nonBlankPages.size(),
                "batchCount", batches.size(), "maxAttempts", pageMaxAttempts),
                "AI analysis started");

        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        try {
            List<Future<BatchAnalysisResult>> futures = new ArrayList<>(batches.size());
            for (List<PageContent> batch : batches) {
                futures.add(executor.submit(StructuredLogContext.wrap(() -> analyzeBatchWithRetry(
                        batch, pageRetryCount, providerCallCount, systemPrompt, responseSchema))));
            }

            List<DiscoveredSensitiveContent> discoveries = new ArrayList<>();
            ProviderMetrics aggregate = new ProviderMetrics();
            for (Future<BatchAnalysisResult> future : futures) {
                try {
                    BatchAnalysisResult result = future.get();
                    discoveries.addAll(result.findings());
                    aggregate.add(result.metrics());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Local AI provider analysis was interrupted", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new IllegalStateException("Local AI provider batch analysis failed", cause);
                }
            }

            StructuredLogContext.info(log, "AI_ANALYSIS_COMPLETED", Map.of(
                    "provider", "ollama", "model", model, "pageCount", nonBlankPages.size(),
                    "batchCount", batches.size(), "providerCalls", providerCallCount.get(),
                    "findingCount", discoveries.size(), "pageRetries", pageRetryCount.get(),
                    "durationMs", elapsedMillis(startedNanos)), "AI analysis completed");
            return new AnalysisOutput(discoveries);
        } catch (RuntimeException e) {
            String reason = e instanceof TechnicalDependencyException technical
                    ? technical.failureCode().name()
                    : e.getClass().getSimpleName();
            int attempts = e instanceof TechnicalDependencyException technical
                    ? technical.attempts()
                    : 1;
            StructuredLogContext.warn(log, "AI_ANALYSIS_FAILED", Map.of(
                    "provider", "ollama", "model", model, "pageCount", nonBlankPages.size(),
                    "batchCount", batches.size(), "providerCalls", providerCallCount.get(),
                    "reasonCode", reason, "attempt", attempts, "pageRetries", pageRetryCount.get(),
                    "durationMs", elapsedMillis(startedNanos)), "AI analysis failed");
            throw e;
        } finally {
            executor.shutdownNow();
        }
    }

    private List<List<PageContent>> buildBatches(List<PageContent> pages) {
        List<List<PageContent>> batches = new ArrayList<>();
        List<PageContent> current = new ArrayList<>();
        int chars = 0;
        for (PageContent page : pages) {
            int nextChars = page.text().length();
            if (!current.isEmpty() && (current.size() >= pageBatchSize || chars + nextChars > pageBatchMaxCharacters)) {
                batches.add(List.copyOf(current));
                current.clear();
                chars = 0;
            }
            current.add(page);
            chars += nextChars;
        }
        if (!current.isEmpty()) {
            batches.add(List.copyOf(current));
        }
        return batches;
    }

    /**
     * Retries only the affected page batch; successful batches are not replayed.
     *
     * <p>If a multi-page batch repeatedly returns unusable structured output, the batch is
     * split into individual page requests as a final bounded recovery step. This keeps the
     * recovery local to the failed batch and prevents the outer document-level resilience
     * decorator from replaying already-successful AI work.
     */
    private BatchAnalysisResult analyzeBatchWithRetry(List<PageContent> pages,
                                                       AtomicInteger pageRetryCount,
                                                       AtomicInteger providerCallCount,
                                                       String systemPrompt,
                                                       JsonNode responseSchema) {
        int attempt = 0;
        long batchStartedNanos = System.nanoTime();
        while (true) {
            attempt++;
            long attemptStartedNanos = System.nanoTime();
            try {
                BatchAnalysisResult result = analyzeBatch(pages, systemPrompt, responseSchema, providerCallCount);
                if (attempt > 1) {
                    log.info("AI_BATCH_RECOVERED provider=ollama model={} pages={} attempts={} durationMs={}",
                            model, pageNumbers(pages), attempt, elapsedMillis(batchStartedNanos));
                }
                return result;
            } catch (TechnicalDependencyException e) {
                boolean retrying = e.retryable() && attempt < pageMaxAttempts;
                boolean splitFallback = !retrying
                        && e.retryable()
                        && e.failureCode() == TechnicalFailureCode.AI_INVALID_RESPONSE
                        && pages.size() > 1;
                log.warn("AI_BATCH_ATTEMPT_FAILED provider=ollama model={} pages={} attempt={} maxAttempts={} reason={} retrying={} splitFallback={} durationMs={}",
                        model, pageNumbers(pages), attempt, pageMaxAttempts, e.failureCode(), retrying, splitFallback,
                        elapsedMillis(attemptStartedNanos));
                if (retrying) {
                    pageRetryCount.incrementAndGet();
                    sleepPageBackoff(attempt);
                    continue;
                }
                if (splitFallback) {
                    return analyzeSplitBatchAfterInvalidResponse(
                            pages, pageRetryCount, providerCallCount, systemPrompt, responseSchema, attempt);
                }
                if (e.retryable()) {
                    throw exhaustedBatchFailure(e, attempt);
                }
                throw e.withAttempts(attempt);
            }
        }
    }

    private BatchAnalysisResult analyzeSplitBatchAfterInvalidResponse(List<PageContent> pages,
                                                                       AtomicInteger pageRetryCount,
                                                                       AtomicInteger providerCallCount,
                                                                       String systemPrompt,
                                                                       JsonNode responseSchema,
                                                                       int failedBatchAttempts) {
        long startedNanos = System.nanoTime();
        int callsBefore = providerCallCount.get();
        log.warn("AI_BATCH_SPLIT_FALLBACK_STARTED provider=ollama model={} pages={} failedBatchAttempts={} splitPages={}",
                model, pageNumbers(pages), failedBatchAttempts, pages.size());

        List<DiscoveredSensitiveContent> findings = new ArrayList<>();
        ProviderMetrics aggregate = new ProviderMetrics();
        for (PageContent page : pages) {
            try {
                BatchAnalysisResult pageResult = analyzeBatchWithRetry(
                        List.of(page), pageRetryCount, providerCallCount, systemPrompt, responseSchema);
                findings.addAll(pageResult.findings());
                aggregate.add(pageResult.metrics());
            } catch (TechnicalDependencyException e) {
                log.warn("AI_BATCH_SPLIT_FALLBACK_FAILED provider=ollama model={} originalPages={} failedPage={} reason={} attempts={} durationMs={}",
                        model, pageNumbers(pages), page.pageNumber(), e.failureCode(), e.attempts(),
                        elapsedMillis(startedNanos));
                throw e;
            }
        }

        log.info("AI_BATCH_SPLIT_FALLBACK_RECOVERED provider=ollama model={} pages={} splitPages={} providerCalls={} durationMs={}",
                model, pageNumbers(pages), pages.size(), providerCallCount.get() - callsBefore,
                elapsedMillis(startedNanos));
        return new BatchAnalysisResult(findings, aggregate);
    }

    private BatchAnalysisResult analyzeBatch(List<PageContent> pages,
                                              String systemPrompt,
                                              JsonNode responseSchema,
                                              AtomicInteger providerCallCount) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("stream", false);
        requestBody.put("keep_alive", keepAlive);
        requestBody.put("think", thinkingEnabled);
        requestBody.put("format", structuredOutput ? responseSchema : "json");

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", 0);
        if (contextWindowTokens > 0) {
            options.put("num_ctx", contextWindowTokens);
        }
        if (maxOutputTokens > 0) {
            options.put("num_predict", maxOutputTokens);
        }
        requestBody.put("options", options);
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", buildBatchUserPrompt(pages))
        ));

        final String body;
        try {
            body = objectMapper.writeValueAsString(requestBody);
        } catch (IOException e) {
            throw technical(TechnicalFailureCode.AI_INVALID_RESPONSE, false,
                    "Local AI request could not be prepared", e);
        }

        HttpRequest request = HttpRequest.newBuilder(chatEndpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        final HttpResponse<String> response;
        providerCallCount.incrementAndGet();
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw technical(TechnicalFailureCode.AI_TIMEOUT, true,
                    "Local AI provider timed out", e);
        } catch (ConnectException e) {
            throw technical(TechnicalFailureCode.AI_UNAVAILABLE, true,
                    "Local AI provider is unavailable", e);
        } catch (IOException e) {
            throw technical(TechnicalFailureCode.AI_UNAVAILABLE, true,
                    "Local AI provider request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw technical(TechnicalFailureCode.AI_UNAVAILABLE, false,
                    "Local AI provider analysis was interrupted", e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            if (status == 429) {
                throw technical(TechnicalFailureCode.AI_RATE_LIMITED, true,
                        "Local AI provider is rate limited", null);
            }
            if (status == 500 || status == 502 || status == 503 || status == 504) {
                throw technical(TechnicalFailureCode.AI_UNAVAILABLE, true,
                        "Local AI provider is temporarily unavailable", null);
            }
            throw technical(TechnicalFailureCode.AI_REJECTED, false,
                    "Local AI provider rejected the request", null);
        }

        try {
            JsonNode envelope = objectMapper.readTree(response.body());
            String content = envelope.path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw technical(TechnicalFailureCode.AI_INVALID_RESPONSE, true,
                        "Local AI provider returned no analysis content", null);
            }
            String doneReason = envelope.path("done_reason").asText("");
            if ("length".equalsIgnoreCase(doneReason)) {
                throw technical(TechnicalFailureCode.AI_INVALID_RESPONSE, true,
                        "Local AI provider reached the configured output limit", null);
            }
            List<DiscoveredSensitiveContent> findings = responseParser.parse(
                    content, pages, "ollama", model, "ollama:" + model, VERSION);
            return new BatchAnalysisResult(findings, ProviderMetrics.from(envelope));
        } catch (TechnicalDependencyException e) {
            throw e;
        } catch (IOException e) {
            throw technical(TechnicalFailureCode.AI_INVALID_RESPONSE, true,
                    "Local AI provider response could not be processed", e);
        }
    }

    private String buildBatchUserPrompt(List<PageContent> pages) {
        StringBuilder builder = new StringBuilder("Analyze each page independently. Every finding must include its pageNumber.\n");
        for (PageContent page : pages) {
            builder.append("<PAGE number=\"").append(page.pageNumber()).append("\">\n")
                    .append(page.text())
                    .append("\n</PAGE>\n");
        }
        return builder.toString();
    }

    private JsonNode buildResponseSchema() {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("type", "object");
        finding.put("additionalProperties", false);
        finding.put("properties", Map.of(
                "pageNumber", Map.of("type", "integer", "minimum", 1),
                "entityType", Map.of("type", "string", "enum", List.of("SSN", "DATE_OF_BIRTH", "CREDIT_CARD_NUMBER", "CREDIT_SCORE")),
                "matchedText", Map.of("type", "string"),
                "contextText", Map.of("type", "string"),
                "confidence", Map.of("type", "number", "minimum", 0, "maximum", 1)
        ));
        finding.put("required", List.of("pageNumber", "entityType", "matchedText", "contextText", "confidence"));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("additionalProperties", false);
        root.put("properties", Map.of("findings", Map.of("type", "array", "items", finding)));
        root.put("required", List.of("findings"));
        return objectMapper.valueToTree(root);
    }

    private String pageNumbers(List<PageContent> pages) {
        return pages.stream().map(page -> Integer.toString(page.pageNumber())).reduce((a, b) -> a + "," + b).orElse("");
    }

    private TechnicalDependencyException exhaustedBatchFailure(TechnicalDependencyException failure, int attempts) {
        return new TechnicalDependencyException(
                failure.dependency(), failure.failureCode(), false, failure.getMessage(), failure).withAttempts(attempts);
    }

    private void sleepPageBackoff(int completedAttempt) {
        long baseMillis = Math.max(0L, pageRetryBackoff.toMillis());
        long sleepMillis = Math.min(5_000L, baseMillis * Math.max(1, completedAttempt));
        if (sleepMillis <= 0L) return;
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw technical(TechnicalFailureCode.AI_UNAVAILABLE, false,
                    "Local AI provider retry was interrupted", e);
        }
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private String formatRate(long tokens, long durationNanos) {
        if (tokens <= 0 || durationNanos <= 0) return "0.0";
        double rate = tokens / (durationNanos / 1_000_000_000.0d);
        return String.format(java.util.Locale.ROOT, "%.1f", rate);
    }

    private TechnicalDependencyException technical(TechnicalFailureCode code, boolean retryable,
                                                    String safeMessage, Throwable cause) {
        return new TechnicalDependencyException(TechnicalDependency.AI, code, retryable, safeMessage, cause);
    }

    private record BatchAnalysisResult(List<DiscoveredSensitiveContent> findings, ProviderMetrics metrics) { }

    private static final class ProviderMetrics {
        private final AtomicLong loadDurationNanos = new AtomicLong();
        private final AtomicLong promptEvalCount = new AtomicLong();
        private final AtomicLong promptEvalDurationNanos = new AtomicLong();
        private final AtomicLong evalCount = new AtomicLong();
        private final AtomicLong evalDurationNanos = new AtomicLong();

        static ProviderMetrics from(JsonNode envelope) {
            ProviderMetrics result = new ProviderMetrics();
            result.loadDurationNanos.set(envelope.path("load_duration").asLong(0));
            result.promptEvalCount.set(envelope.path("prompt_eval_count").asLong(0));
            result.promptEvalDurationNanos.set(envelope.path("prompt_eval_duration").asLong(0));
            result.evalCount.set(envelope.path("eval_count").asLong(0));
            result.evalDurationNanos.set(envelope.path("eval_duration").asLong(0));
            return result;
        }

        void add(ProviderMetrics other) {
            if (other == null) return;
            loadDurationNanos.addAndGet(other.loadDurationNanos.get());
            promptEvalCount.addAndGet(other.promptEvalCount.get());
            promptEvalDurationNanos.addAndGet(other.promptEvalDurationNanos.get());
            evalCount.addAndGet(other.evalCount.get());
            evalDurationNanos.addAndGet(other.evalDurationNanos.get());
        }
    }
}
