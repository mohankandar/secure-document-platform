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


    /** Task 0083: the second check's own prompt and answer, one page a request; only what stands on the page counts. */
    @Test
    void theSecondCheckAsksItsOwnPromptPageByPage() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        java.util.List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        startServer(exchange -> {
            requests.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, envelope("{\"found\":[{\"kind\":\"ROUTING_NUMBER\",\"value\":\"021000021\"},"
                    + "{\"kind\":\"SSN\",\"value\":\"not there\"},{\"kind\":\"FACE_PHOTO\",\"value\":\"021000021\"}]}"));
        });
        var output = batchedAdapter(1, 2).analyze(new com.securedoc.platform.application.port.out
                .SensitiveContentAnalysisPort.AnalysisRequest(null, List.of(new PageContent(1, "Routing 021000021"),
                new PageContent(2, "Nothing here")), com.securedoc.platform.application.port.out
                .SensitiveContentAnalysisPort.AnalysisPolicy.ofSecondCheck()));
        assertEquals(2, requests.get());
        assertEquals(List.of("ROUTING_NUMBER"), output.findings().stream().map(f -> f.entityType()).toList());
        assertEquals(1, output.findings().get(0).pageNumber());
        assertEquals("text-second-check-text-v1", output.models().get(0).version());
        assertEquals(0, bodies.stream().filter(b -> !b.contains("Find personal and financial identifiers")).count());
        assertEquals(0, bodies.stream().filter(b -> !b.contains("\"properties\":{\"kind\":")).count(), "kind first");
    }

    /** Task 0065: a document's beginning has its own prompt; a name that does not stand in the text is dropped. */
    @Test
    void theFirstPassAsksForThePeopleAndDropsWhatIsNotThere() throws Exception {
        java.util.List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        startServer(exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 200, envelope("{\"kind\":\"Complaint\",\"matter\":\"m\",\"people\":[{\"name\":\"Hope Banks\",\"role\":\"r\","
                    + "\"alsoCalled\":[\"Banks\",\"Hopeless\"]},{\"name\":\"Nobody Here\",\"role\":\"r\",\"alsoCalled\":[]}]}"));
        });
        var output = batchedAdapter(1, 2).analyze(request(com.securedoc.platform.application.port.out
                .SensitiveContentAnalysisPort.AnalysisPolicy.Ask.SUMMARY, "HOPE\nBANKS, called Banks"));
        assertEquals(List.of("SUMMARY_PERSON:Hope Banks", "SUMMARY_ALSO_CALLED:Banks"), output.findings().stream()
                .map(f -> f.entityType() + ":" + f.matchedText()).toList());
        assertEquals("text-document-summary-v1", output.models().get(0).version());
        assertEquals(1, bodies.size());
        assertEquals(true, bodies.get(0).contains("\"properties\":{\"kind\":"), "in the measured order");
    }

    /** Task 0065: ten sentences a request; a sentence without a usable answer is taken as a person. */
    @Test
    void knownNamesAreAskedInTensAndAnUnansweredOneIsAPerson() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            send(exchange, 200, envelope("{\"answers\":[{\"number\":1,\"is\":\"thing_or_word\"},{\"number\":2,\"is\":\"person\"},"
                    + "{\"number\":3,\"is\":\"unheard of\"}," + java.util.stream.IntStream.rangeClosed(4, 10)
                    .mapToObj(n -> "{\"number\":" + n + ",\"is\":\"place_or_street\"}").collect(java.util.stream.Collectors.joining(",")) + "]}"));
        });
        String[] sentences = java.util.stream.IntStream.rangeClosed(1, 12).mapToObj(n -> "\"Banks\" in: " + n).toArray(String[]::new);
        var output = batchedAdapter(1, 2).analyze(request(com.securedoc.platform.application.port.out
                .SensitiveContentAnalysisPort.AnalysisPolicy.Ask.KNOWN_NAMES, sentences));
        assertEquals(2, requests.get());
        assertEquals(List.of(2, 3, 12), output.findings().stream().map(f -> f.pageNumber()).toList());
        assertEquals("text-known-names-v1", output.models().get(0).version());
    }

    private static com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisRequest request(
            com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisPolicy.Ask ask, String... texts) {
        List<PageContent> pages = new java.util.ArrayList<>();
        for (String text : texts) pages.add(new PageContent(pages.size() + 1, text));
        return new com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisRequest(null, pages,
                com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisPolicy.of(ask));
    }

    @Test
    void retriesInvalidStructuredResponseAndRecoversTheSameBatch() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            int requestNumber = requests.incrementAndGet();
            if (requestNumber == 1) {
                send(exchange, 200, envelope("not-json"));
                return;
            }
            send(exchange, 200, envelope("{\"findings\":[]}"));
        });

        OllamaSensitiveContentAnalysisAdapter adapter = batchedAdapter(1, 2);
        adapter.analyze(List.of(
                new PageContent(1, "PAGE_ONE"),
                new PageContent(2, "PAGE_TWO")));

        assertEquals(2, requests.get(), "malformed provider output should retry the affected batch once");
    }

    @Test
    void splitsRepeatedlyInvalidBatchIntoIndividualPagesBeforeFailingDocument() throws Exception {
        AtomicInteger batchRequests = new AtomicInteger();
        AtomicInteger pageOneRequests = new AtomicInteger();
        AtomicInteger pageTwoRequests = new AtomicInteger();
        startServer(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean pageOne = body.contains("PAGE_ONE");
            boolean pageTwo = body.contains("PAGE_TWO");
            if (pageOne && pageTwo) {
                batchRequests.incrementAndGet();
                send(exchange, 200, envelope("not-json"));
                return;
            }
            if (pageOne) pageOneRequests.incrementAndGet();
            if (pageTwo) pageTwoRequests.incrementAndGet();
            send(exchange, 200, envelope("{\"findings\":[]}"));
        });

        OllamaSensitiveContentAnalysisAdapter adapter = batchedAdapter(1, 2);
        adapter.analyze(List.of(
                new PageContent(1, "PAGE_ONE"),
                new PageContent(2, "PAGE_TWO")));

        assertEquals(2, batchRequests.get(), "configured batch attempts should be exhausted first");
        assertEquals(1, pageOneRequests.get(), "split fallback should analyze page one independently");
        assertEquals(1, pageTwoRequests.get(), "split fallback should analyze page two independently");
    }

    @Test
    void exhaustedInvalidSinglePageIsNonRetryableForOuterDocumentDecorator() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            send(exchange, 200, envelope("not-json"));
        });

        OllamaSensitiveContentAnalysisAdapter adapter = batchedAdapter(1, 2);
        ResilientSensitiveContentAnalysisPort resilient = new ResilientSensitiveContentAnalysisPort(
                adapter, null, new SelectiveRetryExecutor(2, Duration.ZERO));

        TechnicalDependencyException failure = assertThrows(
                TechnicalDependencyException.class,
                () -> resilient.analyze(List.of(new PageContent(1, "PAGE_ONE"))));

        assertEquals(2, requests.get(), "outer resilience must not replay the whole document after targeted attempts");
        assertEquals(TechnicalFailureCode.AI_INVALID_RESPONSE, failure.failureCode());
        assertEquals(2, failure.attempts());
        assertFalse(failure.retryable());
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

    private OllamaSensitiveContentAnalysisAdapter batchedAdapter(int parallelism, int pageMaxAttempts) {
        return new OllamaSensitiveContentAnalysisAdapter(
                "http://localhost:" + server.getAddress().getPort(),
                "test-model",
                Duration.ofSeconds(5),
                parallelism,
                pageMaxAttempts,
                Duration.ZERO,
                "30m",
                true,
                false,
                8192,
                2048,
                2,
                14_000);
    }

    private String envelope(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"message\":{\"content\":\"" + escaped + "\"},\"done_reason\":\"stop\"}";
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
