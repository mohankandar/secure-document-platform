package com.securedoc.platform.api.policy;

import com.securedoc.platform.ai.analysis.OllamaInferencePoolSensitiveContentAnalysisAdapter;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.PageContent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaInferencePoolTest {

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void stopServers() {
        for (HttpServer server : servers) {
            server.stop(0);
        }
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
        }
    }

    @Test
    void distributesRequestsAcrossHealthyHomogeneousNodes() throws Exception {
        AtomicInteger nodeOneCalls = new AtomicInteger();
        AtomicInteger nodeTwoCalls = new AtomicInteger();
        NodeServer nodeOne = startNode("test-model", exchange -> {
            nodeOneCalls.incrementAndGet();
            sendSuccess(exchange);
        });
        NodeServer nodeTwo = startNode("test-model", exchange -> {
            nodeTwoCalls.incrementAndGet();
            sendSuccess(exchange);
        });

        OllamaInferencePoolSensitiveContentAnalysisAdapter pool = pool(
                List.of(node("ai-1", nodeOne), node("ai-2", nodeTwo)), 1, 2,
                Duration.ZERO, Duration.ofMillis(100), Duration.ofMillis(50));

        for (int i = 0; i < 4; i++) {
            pool.analyze(List.of(new PageContent(1, "PAGE_" + i)));
        }

        assertEquals(2, nodeOneCalls.get());
        assertEquals(2, nodeTwoCalls.get());
    }

    @Test
    void failsOverAfterTargetedNodeRetriesAreExhausted() throws Exception {
        AtomicInteger failedNodeCalls = new AtomicInteger();
        AtomicInteger healthyNodeCalls = new AtomicInteger();
        NodeServer failed = startNode("test-model", exchange -> {
            failedNodeCalls.incrementAndGet();
            send(exchange, 503, "{}");
        });
        NodeServer healthy = startNode("test-model", exchange -> {
            healthyNodeCalls.incrementAndGet();
            sendSuccess(exchange);
        });

        OllamaInferencePoolSensitiveContentAnalysisAdapter pool = pool(
                List.of(node("ai-1", failed), node("ai-2", healthy)), 1, 2,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMillis(50));

        pool.analyze(List.of(new PageContent(1, "PAGE_ONE")));

        assertEquals(1, failedNodeCalls.get(), "failed node should consume only its targeted attempt");
        assertEquals(1, healthyNodeCalls.get(), "request should continue on the healthy node");
        var snapshots = pool.nodeSnapshots();
        assertTrue(snapshots.stream().anyMatch(snapshot -> snapshot.id().equals("ai-1") && snapshot.circuitOpen()));
    }

    @Test
    void excludesReachableNodeWhenRequiredModelIsMissing() throws Exception {
        AtomicInteger wrongModelCalls = new AtomicInteger();
        AtomicInteger correctModelCalls = new AtomicInteger();
        NodeServer wrongModel = startNode("different-model", exchange -> {
            wrongModelCalls.incrementAndGet();
            sendSuccess(exchange);
        });
        NodeServer correctModel = startNode("test-model", exchange -> {
            correctModelCalls.incrementAndGet();
            sendSuccess(exchange);
        });

        OllamaInferencePoolSensitiveContentAnalysisAdapter pool = pool(
                List.of(node("wrong-model", wrongModel), node("correct-model", correctModel)), 1, 2,
                Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofMillis(50));

        pool.analyze(List.of(new PageContent(1, "PAGE_ONE")));

        assertEquals(0, wrongModelCalls.get(), "model-mismatched node must never receive customer content");
        assertEquals(1, correctModelCalls.get());
        assertTrue(pool.nodeSnapshots().stream().anyMatch(snapshot ->
                snapshot.id().equals("wrong-model")
                        && snapshot.health() == OllamaInferencePoolSensitiveContentAnalysisAdapter.NodeHealth.MODEL_MISMATCH));
    }

    @Test
    void boundedQueueFailsSafelyWhenAllHealthyCapacityIsBusy() throws Exception {
        CountDownLatch firstCallEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCall = new CountDownLatch(1);
        NodeServer node = startNode("test-model", exchange -> {
            firstCallEntered.countDown();
            try {
                if (!releaseFirstCall.await(3, TimeUnit.SECONDS)) {
                    send(exchange, 503, "{}");
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                send(exchange, 503, "{}");
                return;
            }
            sendSuccess(exchange);
        });

        OllamaInferencePoolSensitiveContentAnalysisAdapter pool = pool(
                List.of(node("ai-1", node)), 1, 1,
                Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofMillis(75));

        ExecutorService caller = Executors.newSingleThreadExecutor();
        executors.add(caller);
        Future<?> first = caller.submit(() -> pool.analyze(List.of(new PageContent(1, "FIRST"))));
        assertTrue(firstCallEntered.await(2, TimeUnit.SECONDS));

        TechnicalDependencyException saturated = assertThrows(
                TechnicalDependencyException.class,
                () -> pool.analyze(List.of(new PageContent(1, "SECOND"))));

        assertEquals(TechnicalFailureCode.AI_UNAVAILABLE, saturated.failureCode());
        assertTrue(saturated.getMessage().contains("QUEUE_TIMEOUT"));
        assertTrue(saturated.retryable(), "zero-dispatch saturation may be retried only by the bounded outer policy");
        releaseFirstCall.countDown();
        first.get(3, TimeUnit.SECONDS);
    }


    @Test
    void busyHealthyNodeIsNotDemotedByCompetingStaleHealthProbe() throws Exception {
        CountDownLatch firstCallEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCall = new CountDownLatch(1);
        AtomicInteger tagCalls = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/api/tags", exchange -> {
            if (tagCalls.incrementAndGet() == 1) {
                send(exchange, 200, "{\"models\":[{\"name\":\"test-model\",\"model\":\"test-model\"}]}");
            } else {
                // Reproduces a provider whose readiness endpoint cannot respond while inference is busy.
                send(exchange, 503, "{}");
            }
        });
        server.createContext("/api/chat", exchange -> {
            firstCallEntered.countDown();
            try {
                if (!releaseFirstCall.await(3, TimeUnit.SECONDS)) {
                    send(exchange, 503, "{}");
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                send(exchange, 503, "{}");
                return;
            }
            sendSuccess(exchange);
        });
        server.start();
        servers.add(server);
        executors.add(serverExecutor);
        NodeServer node = new NodeServer(server);

        OllamaInferencePoolSensitiveContentAnalysisAdapter pool = pool(
                List.of(node("ai-busy", node)), 1, 1,
                Duration.ofMillis(20), Duration.ofMillis(100), Duration.ofMillis(75));

        ExecutorService caller = Executors.newSingleThreadExecutor();
        executors.add(caller);
        Future<?> first = caller.submit(() -> pool.analyze(List.of(new PageContent(1, "FIRST"))));
        assertTrue(firstCallEntered.await(2, TimeUnit.SECONDS));
        Thread.sleep(35);

        TechnicalDependencyException saturated = assertThrows(
                TechnicalDependencyException.class,
                () -> pool.analyze(List.of(new PageContent(1, "SECOND"))));

        assertEquals(TechnicalFailureCode.AI_UNAVAILABLE, saturated.failureCode());
        assertTrue(saturated.getMessage().contains("QUEUE_TIMEOUT"));
        assertEquals(1, tagCalls.get(), "busy healthy node must not be re-probed and falsely demoted");
        releaseFirstCall.countDown();
        first.get(3, TimeUnit.SECONDS);
    }


    @Test
    void allUnhealthyNodesReportExplicitPoolReason() throws Exception {
        NodeServer wrongModel = startNode("different-model", this::sendSuccess);

        OllamaInferencePoolSensitiveContentAnalysisAdapter pool = pool(
                List.of(node("wrong-model", wrongModel)), 1, 1,
                Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ZERO);

        TechnicalDependencyException unavailable = assertThrows(
                TechnicalDependencyException.class,
                () -> pool.analyze(List.of(new PageContent(1, "PAGE_ONE"))));

        assertEquals(TechnicalFailureCode.AI_UNAVAILABLE, unavailable.failureCode());
        assertTrue(unavailable.getMessage().contains("ALL_NODES_UNHEALTHY"));
    }

    @Test
    void recoveredNodeRejoinsAfterCircuitCooldownAndHealthyProbe() throws Exception {
        AtomicBoolean failNodeOne = new AtomicBoolean(true);
        AtomicInteger nodeOneCalls = new AtomicInteger();
        AtomicInteger nodeTwoCalls = new AtomicInteger();
        NodeServer nodeOne = startNode("test-model", exchange -> {
            nodeOneCalls.incrementAndGet();
            if (failNodeOne.get()) {
                send(exchange, 503, "{}");
            } else {
                sendSuccess(exchange);
            }
        });
        NodeServer nodeTwo = startNode("test-model", exchange -> {
            nodeTwoCalls.incrementAndGet();
            sendSuccess(exchange);
        });

        OllamaInferencePoolSensitiveContentAnalysisAdapter pool = pool(
                List.of(node("ai-1", nodeOne), node("ai-2", nodeTwo)), 1, 2,
                Duration.ofMillis(20), Duration.ofMillis(40), Duration.ofMillis(50));

        pool.analyze(List.of(new PageContent(1, "FIRST")));
        assertEquals(1, nodeOneCalls.get());
        assertEquals(1, nodeTwoCalls.get());

        failNodeOne.set(false);
        Thread.sleep(70);
        // Two calls avoid depending on the round-robin tie position after the recovery probe.
        pool.analyze(List.of(new PageContent(1, "SECOND")));
        pool.analyze(List.of(new PageContent(1, "THIRD")));

        assertTrue(nodeOneCalls.get() >= 2, "recovered node should return to routing rotation");
        assertFalse(pool.nodeSnapshots().stream().filter(snapshot -> snapshot.id().equals("ai-1"))
                .findFirst().orElseThrow().circuitOpen());
    }

    private OllamaInferencePoolSensitiveContentAnalysisAdapter pool(
            List<OllamaInferencePoolSensitiveContentAnalysisAdapter.NodeDefinition> nodes,
            int maxConcurrent,
            int maxNodeAttempts,
            Duration healthInterval,
            Duration circuitOpen,
            Duration queueWait) {
        List<OllamaInferencePoolSensitiveContentAnalysisAdapter.NodeDefinition> bounded = nodes.stream()
                .map(node -> new OllamaInferencePoolSensitiveContentAnalysisAdapter.NodeDefinition(
                        node.id(), node.baseUrl(), maxConcurrent))
                .toList();
        return new OllamaInferencePoolSensitiveContentAnalysisAdapter(
                bounded,
                "test-model",
                Duration.ofSeconds(2),
                1,
                1,
                Duration.ZERO,
                "5m",
                true,
                false,
                0,
                0,
                1,
                14_000,
                Duration.ofMillis(300),
                healthInterval,
                circuitOpen,
                queueWait,
                maxNodeAttempts);
    }

    private OllamaInferencePoolSensitiveContentAnalysisAdapter.NodeDefinition node(String id, NodeServer server) {
        return new OllamaInferencePoolSensitiveContentAnalysisAdapter.NodeDefinition(
                id, "http://localhost:" + server.server().getAddress().getPort(), 1);
    }

    private NodeServer startNode(String exposedModel, ExchangeHandler chatHandler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/api/tags", exchange -> send(exchange, 200,
                "{\"models\":[{\"name\":\"" + exposedModel + "\",\"model\":\"" + exposedModel + "\"}]}"));
        server.createContext("/api/chat", chatHandler::handle);
        server.start();
        servers.add(server);
        executors.add(executor);
        return new NodeServer(server);
    }

    private void sendSuccess(HttpExchange exchange) throws IOException {
        send(exchange, 200, "{\"message\":{\"content\":\"{\\\"findings\\\":[]}\"},\"done_reason\":\"stop\"}");
    }

    private void send(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record NodeServer(HttpServer server) {
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
