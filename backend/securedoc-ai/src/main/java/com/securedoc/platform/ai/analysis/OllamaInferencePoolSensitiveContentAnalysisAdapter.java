package com.securedoc.platform.ai.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.observability.StructuredLogContext;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HA/load-balancing wrapper for a homogeneous pool of Ollama inference nodes.
 *
 * <p>Every node in a pool is required to expose the exact configured model. The pool never
 * substitutes a different model. Nodes are health/model probed via {@code /api/tags}, bounded by
 * per-node concurrency, and temporarily removed from rotation after retryable provider failures.
 * When all healthy nodes are saturated, callers wait only for the configured bounded queue window.
 *
 * <p>The wrapper intentionally sits behind the provider-neutral application port. This keeps the
 * current on-prem deployment simple while preserving a clean boundary for future SaaS/shared or
 * dedicated inference pools.
 */
public final class OllamaInferencePoolSensitiveContentAnalysisAdapter implements SensitiveContentAnalysisPort {

    private static final Logger log = LoggerFactory.getLogger(OllamaInferencePoolSensitiveContentAnalysisAdapter.class);
    private static final int MAX_POOL_NODES = 32;

    private final List<NodeState> nodes;
    private final String model;
    private final Duration healthProbeTimeout;
    private final Duration healthCheckInterval;
    private final Duration circuitOpenDuration;
    private final Duration queueWait;
    private final int maxNodeAttempts;
    private final HttpClient healthClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger roundRobinCursor = new AtomicInteger();

    public OllamaInferencePoolSensitiveContentAnalysisAdapter(
            List<NodeDefinition> nodeDefinitions,
            String model,
            Duration requestTimeout,
            int providerParallelism,
            int pageMaxAttempts,
            Duration pageRetryBackoff,
            String keepAlive,
            boolean structuredOutput,
            boolean thinkingEnabled,
            int contextWindowTokens,
            int maxOutputTokens,
            int pageBatchSize,
            int pageBatchMaxCharacters,
            Duration healthProbeTimeout,
            Duration healthCheckInterval,
            Duration circuitOpenDuration,
            Duration queueWait,
            int maxNodeAttempts) {
        Objects.requireNonNull(nodeDefinitions, "nodeDefinitions must not be null");
        if (nodeDefinitions.isEmpty() || nodeDefinitions.size() > MAX_POOL_NODES) {
            throw new IllegalArgumentException("Ollama inference pool must contain between 1 and " + MAX_POOL_NODES + " nodes");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Ollama inference pool model must not be blank");
        }
        if (maxNodeAttempts < 1 || maxNodeAttempts > MAX_POOL_NODES) {
            throw new IllegalArgumentException("Ollama inference pool maxNodeAttempts must be between 1 and " + MAX_POOL_NODES);
        }
        this.model = model.trim();
        this.healthProbeTimeout = positiveDuration(healthProbeTimeout, Duration.ofSeconds(2));
        this.healthCheckInterval = nonNegativeDuration(healthCheckInterval, Duration.ofSeconds(5));
        this.circuitOpenDuration = nonNegativeDuration(circuitOpenDuration, Duration.ofSeconds(10));
        this.queueWait = nonNegativeDuration(queueWait, Duration.ofSeconds(5));
        this.maxNodeAttempts = Math.min(maxNodeAttempts, nodeDefinitions.size());
        this.healthClient = HttpClient.newBuilder().connectTimeout(this.healthProbeTimeout).build();

        Set<String> ids = new HashSet<>();
        Set<String> urls = new HashSet<>();
        List<NodeState> built = new ArrayList<>(nodeDefinitions.size());
        for (NodeDefinition definition : nodeDefinitions) {
            Objects.requireNonNull(definition, "Ollama inference node definition must not be null");
            String id = normalizeNodeId(definition.id());
            String baseUrl = normalizeBaseUrl(definition.baseUrl());
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate Ollama inference node id: " + id);
            }
            if (!urls.add(baseUrl)) {
                throw new IllegalArgumentException("Duplicate Ollama inference node URL configured for node: " + id);
            }
            if (definition.maxConcurrent() < 1 || definition.maxConcurrent() > 64) {
                throw new IllegalArgumentException("Ollama inference node maxConcurrent must be between 1 and 64 for node " + id);
            }
            SensitiveContentAnalysisPort delegate = new OllamaSensitiveContentAnalysisAdapter(
                    baseUrl, this.model, requestTimeout, providerParallelism, pageMaxAttempts, pageRetryBackoff,
                    keepAlive, structuredOutput, thinkingEnabled, contextWindowTokens, maxOutputTokens,
                    pageBatchSize, pageBatchMaxCharacters);
            built.add(new NodeState(id, baseUrl, definition.maxConcurrent(), delegate));
        }
        this.nodes = List.copyOf(built);
    }

    @Override
    public AnalysisOutput analyze(AnalysisRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.pages().stream().allMatch(page -> page.text().isBlank())) {
            return new AnalysisOutput(List.of());
        }

        long startedNanos = System.nanoTime();
        long queueDeadlineNanos = System.nanoTime() + queueWait.toNanos();
        Set<String> attemptedNodeIds = new LinkedHashSet<>();
        TechnicalDependencyException lastRetryableFailure = null;
        int attempts = 0;

        PoolUnavailableReason lastPoolUnavailableReason = null;
        while (attempts < maxNodeAttempts && attemptedNodeIds.size() < nodes.size()) {
            AcquireResult acquired = acquireNode(attemptedNodeIds, queueDeadlineNanos);
            NodeLease lease = acquired.lease();
            if (lease == null) {
                lastPoolUnavailableReason = acquired.reason();
                StructuredLogContext.warn(log, "AI_INFERENCE_POOL_UNAVAILABLE", Map.of(
                        "provider", "ollama",
                        "model", model,
                        "attempt", Math.max(1, attempts + 1),
                        "maxAttempts", maxNodeAttempts,
                        "inflightCount", totalInflightCount(),
                        "healthyNodeCount", healthyNodeCount(),
                        "configuredNodeCount", nodes.size(),
                        "reasonCode", lastPoolUnavailableReason.name()),
                        "AI inference pool has no dispatchable node");
                break;
            }
            NodeState node = lease.node();
            attemptedNodeIds.add(node.id);
            attempts++;

            StructuredLogContext.info(log, "AI_INFERENCE_NODE_SELECTED", Map.of(
                    "provider", "ollama",
                    "model", model,
                    "inferenceNodeId", node.id,
                    "attempt", attempts,
                    "maxAttempts", maxNodeAttempts,
                    "inflightCount", node.inflight.get(),
                    "healthyNodeCount", healthyNodeCount()),
                    "AI inference node selected");

            try (lease) {
                AnalysisOutput output = node.delegate.analyze(request);
                node.markSuccess();
                StructuredLogContext.info(log, "AI_INFERENCE_NODE_SUCCEEDED", Map.of(
                        "provider", "ollama",
                        "model", model,
                        "inferenceNodeId", node.id,
                        "attempt", attempts,
                        "maxAttempts", maxNodeAttempts,
                        "inflightCount", node.inflight.get(),
                        "durationMs", elapsedMillis(startedNanos),
                        "healthyNodeCount", healthyNodeCount()),
                        "AI inference node succeeded");
                return output;
            } catch (TechnicalDependencyException failure) {
                if (!isNodeFailoverEligible(failure)) {
                    throw failure;
                }
                lastRetryableFailure = failure;
                node.markRetryableFailure(circuitOpenDuration);
                StructuredLogContext.warn(log, "AI_INFERENCE_NODE_FAILED_OVER", Map.of(
                        "provider", "ollama",
                        "model", model,
                        "inferenceNodeId", node.id,
                        "attempt", attempts,
                        "maxAttempts", maxNodeAttempts,
                        "inflightCount", node.inflight.get(),
                        "healthyNodeCount", healthyNodeCount(),
                        "reasonCode", failure.failureCode().name(),
                        "nextNodeEligible", attempts < maxNodeAttempts && attemptedNodeIds.size() < nodes.size(),
                        "durationMs", elapsedMillis(startedNanos)),
                        "AI inference node failed; evaluating failover");
            }
        }

        // If at least one node was already attempted, targeted node/page retries and pool failover
        // have consumed the retry budget. Mark exhaustion non-retryable so the outer document-level
        // resilience decorator cannot replay the whole document again. If zero nodes were attempted
        // (for example temporary saturation/health outage), one bounded outer retry remains useful.
        boolean outerRetryable = attempts == 0;
        String exhaustionReason = lastPoolUnavailableReason == null
                ? "NODE_ATTEMPTS_EXHAUSTED"
                : lastPoolUnavailableReason.name();
        StructuredLogContext.warn(log, "AI_INFERENCE_POOL_EXHAUSTED", Map.of(
                "provider", "ollama",
                "model", model,
                "attempt", Math.max(1, attempts),
                "maxAttempts", maxNodeAttempts,
                "inflightCount", totalInflightCount(),
                "healthyNodeCount", healthyNodeCount(),
                "configuredNodeCount", nodes.size(),
                "reasonCode", exhaustionReason,
                "retryable", outerRetryable),
                "AI inference pool exhausted without a successful inference");
        TechnicalDependencyException exhausted = new TechnicalDependencyException(
                TechnicalDependency.AI,
                TechnicalFailureCode.AI_UNAVAILABLE,
                outerRetryable,
                "No healthy AI inference node with available capacity could complete the request: " + exhaustionReason,
                lastRetryableFailure);
        throw exhausted.withAttempts(Math.max(1, attempts));
    }

    /** Snapshot intended for focused tests/operational diagnostics; contains no prompt/document data. */
    public List<NodeSnapshot> nodeSnapshots() {
        return nodes.stream().map(NodeState::snapshot).toList();
    }

    private AcquireResult acquireNode(Set<String> excludedNodeIds, long queueDeadlineNanos) {
        boolean firstPass = true;
        boolean waitedForCapacity = false;
        while (firstPass || System.nanoTime() <= queueDeadlineNanos) {
            firstPass = false;
            long now = System.nanoTime();
            List<NodeState> candidates = orderedCandidates(excludedNodeIds, now);
            boolean healthyButSaturated = false;
            boolean healthProbePending = false;
            boolean anyHealthy = false;

            for (NodeState node : candidates) {
                refreshHealthIfNeeded(node, now);
                if (node.health.get() != NodeHealth.HEALTHY) {
                    healthProbePending |= node.healthProbeInProgress.get() != 0;
                    continue;
                }
                anyHealthy = true;
                if (node.permits.tryAcquire()) {
                    node.inflight.incrementAndGet();
                    return new AcquireResult(new NodeLease(node), null);
                }
                healthyButSaturated = true;
            }

            if (!healthyButSaturated && !healthProbePending) {
                return new AcquireResult(null, PoolUnavailableReason.ALL_NODES_UNHEALTHY);
            }
            if (System.nanoTime() >= queueDeadlineNanos) {
                if (healthyButSaturated || anyHealthy) {
                    return new AcquireResult(null, waitedForCapacity
                            ? PoolUnavailableReason.QUEUE_TIMEOUT
                            : PoolUnavailableReason.POOL_SATURATED);
                }
                return new AcquireResult(null, PoolUnavailableReason.ALL_NODES_UNHEALTHY);
            }
            waitedForCapacity = true;
            sleepForCapacity(queueDeadlineNanos);
        }
        return new AcquireResult(null, waitedForCapacity
                ? PoolUnavailableReason.QUEUE_TIMEOUT
                : PoolUnavailableReason.POOL_SATURATED);
    }

    private List<NodeState> orderedCandidates(Set<String> excludedNodeIds, long nowNanos) {
        int start = Math.floorMod(roundRobinCursor.getAndIncrement(), nodes.size());
        List<NodeState> candidates = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            NodeState node = nodes.get((start + i) % nodes.size());
            if (!excludedNodeIds.contains(node.id) && node.circuitOpenUntilNanos.get() <= nowNanos) {
                candidates.add(node);
            }
        }
        candidates.sort(Comparator.comparingInt((NodeState node) -> node.inflight.get()));
        return candidates;
    }

    private void refreshHealthIfNeeded(NodeState node, long nowNanos) {
        // An already-healthy node with active leased work is capacity-constrained, not dead.
        // Re-probing it from a competing request can produce a false negative when the provider
        // serializes health and inference traffic. The in-flight request itself remains the
        // authoritative liveness signal until its lease completes or fails.
        if (node.health.get() == NodeHealth.HEALTHY && node.inflight.get() > 0) {
            return;
        }

        long lastProbe = node.lastHealthProbeNanos.get();
        boolean stale = lastProbe == 0L || nowNanos - lastProbe >= healthCheckInterval.toNanos();
        if (lastProbe != 0L && !stale) {
            return;
        }
        if (node.circuitOpenUntilNanos.get() > nowNanos) {
            return;
        }
        // Avoid stampeding the same node when multiple document workers arrive simultaneously.
        if (!node.healthProbeInProgress.compareAndSet(0, 1)) {
            return;
        }
        try {
            probe(node);
        } finally {
            node.healthProbeInProgress.set(0);
        }
    }

    private void probe(NodeState node) {
        long startedNanos = System.nanoTime();
        try {
            URI endpoint = URI.create(node.baseUrl + "/api/tags");
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(healthProbeTimeout)
                    .GET()
                    .build();
            HttpResponse<String> response = healthClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                node.markProbeFailure(circuitOpenDuration);
                return;
            }
            JsonNode root = objectMapper.readTree(response.body());
            boolean modelPresent = false;
            for (JsonNode candidate : root.path("models")) {
                String name = candidate.path("name").asText("");
                String providerModel = candidate.path("model").asText("");
                if (model.equals(name) || model.equals(providerModel)) {
                    modelPresent = true;
                    break;
                }
            }
            node.markProbe(modelPresent ? NodeHealth.HEALTHY : NodeHealth.MODEL_MISMATCH);
            if (!modelPresent) {
                StructuredLogContext.warn(log, "AI_INFERENCE_NODE_MODEL_MISMATCH", Map.of(
                        "provider", "ollama",
                        "model", model,
                        "inferenceNodeId", node.id,
                        "durationMs", elapsedMillis(startedNanos)),
                        "AI inference node does not expose the required model");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            node.markProbeFailure(circuitOpenDuration);
        } catch (IOException | RuntimeException e) {
            node.markProbeFailure(circuitOpenDuration);
        }
    }

    private boolean isNodeFailoverEligible(TechnicalDependencyException failure) {
        // The single-node Ollama adapter intentionally marks exhausted page-level retries
        // non-retryable so an outer document decorator will not replay successful pages. A pool
        // is the one safe layer allowed to move these infrastructure failures to a different node.
        return failure.failureCode() == TechnicalFailureCode.AI_UNAVAILABLE
                || failure.failureCode() == TechnicalFailureCode.AI_TIMEOUT
                || failure.failureCode() == TechnicalFailureCode.AI_RATE_LIMITED;
    }

    private int healthyNodeCount() {
        int count = 0;
        long now = System.nanoTime();
        for (NodeState node : nodes) {
            if (node.health.get() == NodeHealth.HEALTHY && node.circuitOpenUntilNanos.get() <= now) {
                count++;
            }
        }
        return count;
    }

    private int totalInflightCount() {
        return nodes.stream().mapToInt(node -> node.inflight.get()).sum();
    }

    private void sleepForCapacity(long queueDeadlineNanos) {
        long remainingNanos = Math.max(0L, queueDeadlineNanos - System.nanoTime());
        long millis = Math.min(25L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        if (millis <= 0L && remainingNanos > 0L) {
            millis = 1L;
        }
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TechnicalDependencyException(
                    TechnicalDependency.AI,
                    TechnicalFailureCode.AI_UNAVAILABLE,
                    false,
                    "AI inference queue wait was interrupted",
                    e);
        }
    }

    private String normalizeNodeId(String id) {
        if (id == null || !id.trim().matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Ollama inference node id must match [A-Za-z0-9._-]{1,64}");
        }
        return id.trim();
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Ollama inference node baseUrl must not be blank");
        }
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        URI uri = URI.create(normalized);
        if (uri.getScheme() == null || uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Ollama inference node baseUrl must be an absolute http/https URL");
        }
        return normalized;
    }

    private Duration positiveDuration(Duration value, Duration fallback) {
        Duration effective = value == null ? fallback : value;
        if (effective.isZero() || effective.isNegative()) {
            throw new IllegalArgumentException("Duration must be positive");
        }
        return effective;
    }

    private Duration nonNegativeDuration(Duration value, Duration fallback) {
        Duration effective = value == null ? fallback : value;
        if (effective.isNegative()) {
            throw new IllegalArgumentException("Duration must not be negative");
        }
        return effective;
    }

    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
    }

    public record NodeDefinition(String id, String baseUrl, int maxConcurrent) { }

    private record AcquireResult(NodeLease lease, PoolUnavailableReason reason) { }

    private enum PoolUnavailableReason {
        ALL_NODES_UNHEALTHY,
        POOL_SATURATED,
        QUEUE_TIMEOUT
    }

    public enum NodeHealth {
        UNKNOWN,
        HEALTHY,
        UNHEALTHY,
        MODEL_MISMATCH
    }

    public record NodeSnapshot(String id, NodeHealth health, int inflightCount, int maxConcurrent, boolean circuitOpen) { }

    private static final class NodeState {
        private final String id;
        private final String baseUrl;
        private final int maxConcurrent;
        private final SensitiveContentAnalysisPort delegate;
        private final Semaphore permits;
        private final AtomicInteger inflight = new AtomicInteger();
        private final AtomicReference<NodeHealth> health = new AtomicReference<>(NodeHealth.UNKNOWN);
        private final AtomicLong lastHealthProbeNanos = new AtomicLong();
        private final AtomicLong circuitOpenUntilNanos = new AtomicLong();
        private final AtomicInteger healthProbeInProgress = new AtomicInteger();

        private NodeState(String id, String baseUrl, int maxConcurrent, SensitiveContentAnalysisPort delegate) {
            this.id = id;
            this.baseUrl = baseUrl;
            this.maxConcurrent = maxConcurrent;
            this.delegate = delegate;
            this.permits = new Semaphore(maxConcurrent, true);
        }

        private void markProbe(NodeHealth result) {
            health.set(result);
            lastHealthProbeNanos.set(System.nanoTime());
            if (result == NodeHealth.HEALTHY) {
                circuitOpenUntilNanos.set(0L);
            }
        }

        private void markSuccess() {
            health.set(NodeHealth.HEALTHY);
            circuitOpenUntilNanos.set(0L);
        }

        private void markProbeFailure(Duration openDuration) {
            health.set(NodeHealth.UNHEALTHY);
            lastHealthProbeNanos.set(System.nanoTime());
            circuitOpenUntilNanos.set(System.nanoTime() + openDuration.toNanos());
        }

        private void markRetryableFailure(Duration openDuration) {
            health.set(NodeHealth.UNHEALTHY);
            lastHealthProbeNanos.set(System.nanoTime());
            circuitOpenUntilNanos.set(System.nanoTime() + openDuration.toNanos());
        }

        private NodeSnapshot snapshot() {
            return new NodeSnapshot(id, health.get(), inflight.get(), maxConcurrent,
                    circuitOpenUntilNanos.get() > System.nanoTime());
        }
    }

    private final class NodeLease implements AutoCloseable {
        private final NodeState node;
        private boolean closed;

        private NodeLease(NodeState node) {
            this.node = node;
        }

        private NodeState node() {
            return node;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                node.inflight.decrementAndGet();
                node.permits.release();
            }
        }
    }
}
