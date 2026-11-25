package com.securedoc.platform.ai.analysis;

import com.securedoc.platform.application.port.out.PageImagePort.PageImage;
import com.securedoc.platform.domain.finding.AiModel;
import com.securedoc.platform.domain.vision.VisionPageResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0064: the vision model is local, pinned by digest, and anything unclear is a page that was not read. */
class OllamaVisionModelAdapterTest {
    private static final String MODEL = "vl:8b";
    private static final String DIGEST = "ab".repeat(32);
    private static final PageImage IMAGE = new PageImage(new byte[]{1, 2, 3}, 10, 14);

    private HttpServer server;
    private final List<String> digests = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newCachedThreadPool();
    /** The model list is late (6 s, the limit is 5 s) this many times. */
    private final AtomicInteger lateTags = new AtomicInteger();
    private final AtomicInteger tagsAsked = new AtomicInteger();
    private final List<String> asked = new CopyOnWriteArrayList<>();
    private final List<Integer> statuses = new ArrayList<>();
    private String answer = "{\"items\": []}";
    private String doneReason = "stop";

    @BeforeEach
    void start() throws Exception {
        digests.add(DIGEST);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(threads);
        server.createContext("/api/tags", exchange -> {
            if (lateTags.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                try {
                    Thread.sleep(6_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String digest = digests.get(Math.min(tagsAsked.getAndIncrement(), digests.size() - 1));
            String body = digest.isEmpty() ? "{\"models\":[]}" : "{\"models\":[{\"name\":\"other:1\",\"digest\":\"00\"},"
                    + "{\"name\":\"" + MODEL + "\",\"digest\":\"" + digest + "\"}]}";
            send(exchange, 200, body);
        });
        server.createContext("/api/chat", exchange -> {
            asked.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = statuses.isEmpty() ? 200 : statuses.remove(0);
            send(exchange, status, "{\"done\":true,\"done_reason\":\"" + doneReason + "\",\"message\":{\"content\":"
                    + quoted(answer) + "}}");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        threads.shutdownNow();
    }

    /** Task 0080. */
    @Test
    void aModelListThatIsLateOnceIsAskedOnceMore() {
        lateTags.set(1);
        assertTrue(adapter().read(IMAGE, 1).read());
        lateTags.set(2);
        assertNotRead(adapter().read(IMAGE, 1), "LV-AI-002", "TIMEOUT");
    }

    @Test
    void thePromptIsTheBenchmarkedPromptV2() throws Exception {
        try (InputStream in = OllamaVisionModelAdapter.class.getResourceAsStream("/vision-prompt-v2.txt")) {
            assertNotNull(in);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertEquals("dffdfc6fff2ecc967bcc08011aa5ddd7db3ac99fc9fbbc8e25f2bb5a77021337", HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))));
        }
        assertFalse(OllamaVisionModelAdapter.prompt().contains("{COORDINATES}"));
    }

    /** Task 0083 (BL7): any change of a prompt is a new version, with a new measurement. */
    @Test
    void theSecondChecksPromptsAreTheMeasuredOnes() throws Exception {
        assertEquals("70e021bcffc6", sha(SensitiveContentPromptBuilder.secondCheck()));
        assertEquals("4d5391968aba", sha(OllamaVisionModelAdapter.prompt(OllamaVisionModelAdapter.SECOND_CHECK_VERSION)));
        OllamaVisionModelAdapter check = new OllamaVisionModelAdapter("http://127.0.0.1:" + server.getAddress().getPort(),
                MODEL, DIGEST, Duration.ofSeconds(10), null, OllamaVisionModelAdapter.SECOND_CHECK_VERSION);
        assertEquals(new AiModel(MODEL, "second-check-vision-v1", DIGEST), check.read(IMAGE, 1).model());
        assertTrue(asked.get(0).contains("Locate personal and financial identifiers"));
    }

    private static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
    }

    @Test
    void kindsAndBoxesAreKeptAndTheModelIsRecorded() {
        answer = "Here: {\"items\": [{\"type\": \"ssn\", \"box\": [100, 200, 300, 220], \"value\": \"123-45-6789\"}]} done";
        VisionPageResult result = adapter().read(IMAGE, 4);
        assertTrue(result.read());
        assertEquals(4, result.page());
        assertFalse(result.incomplete());
        assertEquals(1, result.items().size());
        assertEquals(VisionPageResult.Kind.SSN, result.items().get(0).kind());
        assertEquals(0.1, result.items().get(0).box().left(), 1e-9);
        assertEquals(0.02, result.items().get(0).box().height(), 1e-9);
        assertEquals(new AiModel(MODEL, "vision-prompt-v2", DIGEST), result.model());
        assertFalse(result.toString().contains("6789"), "a value the model wrote is dropped");
        assertTrue(asked.get(0).contains("\"temperature\":0"));
    }

    @Test
    void anotherDigestIsRefusedWithItsCodeAndNothingIsSent() {
        digests.set(0, "cd".repeat(32));
        assertNotRead(adapter().read(IMAGE, 1), "LV-AI-008", "MODEL_DIGEST_MISMATCH");
        assertTrue(asked.isEmpty(), "the page image never reaches a model that is not the approved one");
    }

    @Test
    void aModelExchangedWhileItAnsweredIsRefused() {
        digests.add("cd".repeat(32));
        assertNotRead(adapter().read(IMAGE, 1), "LV-AI-008", "MODEL_DIGEST_MISMATCH");
    }

    @Test
    void noModelNoServerAndABadAnswerAreNotRead() {
        digests.set(0, "");
        assertNotRead(adapter().read(IMAGE, 1), "LV-AI-004", "MODEL_NOT_INSTALLED");
        digests.set(0, DIGEST);
        for (String bad : List.of("", "no json", "[1]", "{\"items\": {}}", "{\"items\": [")) {
            answer = bad;
            assertNotRead(adapter().read(IMAGE, 1), "LV-AI-005", "ANSWER_NOT_READABLE");
        }
        answer = "{\"items\": []}";
        doneReason = "length";
        assertNotRead(adapter().read(IMAGE, 1), "LV-AI-005", "ANSWER_CUT_SHORT");
        doneReason = "stop";
        statuses.addAll(List.of(500, 500));
        assertNotRead(adapter().read(IMAGE, 1), "LV-AI-001", "HTTP_500");
        statuses.add(503);
        assertTrue(adapter().read(IMAGE, 1).read(), "one passing failure is tried again");
        int port = server.getAddress().getPort();
        server.stop(0);
        assertNotRead(adapter("http://127.0.0.1:" + port).read(IMAGE, 1), "LV-AI-001", "NOT_AVAILABLE");
    }

    @Test
    void anItemThatCannotBePlacedMakesThePageIncomplete() {
        for (String item : List.of("{\"type\": \"SSN\"}", "{\"type\": \"SSN\", \"box\": [1, 2, 3]}",
                "{\"type\": \"SSN\", \"box\": [5, 5, 5, 9]}", "{\"type\": \"SECRET\", \"box\": [1, 2, 3, 4]}",
                "{\"type\": \"SSN\", \"box\": [\"a\", 2, 3, 4]}", "7")) {
            answer = "{\"items\": [" + item + "]}";
            VisionPageResult result = adapter().read(IMAGE, 1);
            assertTrue(result.read() && result.incomplete() && result.items().isEmpty(), item);
        }
    }

    @Test
    void aServerOnTheInternetIsNeverAsked() {
        // A documentation address (RFC 5737): never local, and nothing answers there.
        assertNotRead(adapter("http://203.0.113.7:11434").read(IMAGE, 1), "LV-AI-006", "SERVER_NOT_LOCAL");
    }

    @Test
    void withoutADigestThereIsNoVisionModel() {
        for (String bad : new String[]{null, "", "latest", "abc"}) {
            assertThrows(IllegalArgumentException.class, () -> new OllamaVisionModelAdapter("http://localhost:1", MODEL,
                    bad, Duration.ofSeconds(1), "5m"));
        }
        new OllamaVisionModelAdapter("http://localhost:1", MODEL, "sha256:" + DIGEST, Duration.ofSeconds(1), null);
    }

    private static void assertNotRead(VisionPageResult result, String code, String reason) {
        assertFalse(result.read());
        assertEquals(code, result.code());
        assertEquals(reason, result.reason());
        assertTrue(result.items().isEmpty());
    }

    private OllamaVisionModelAdapter adapter() {
        return adapter("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    private static OllamaVisionModelAdapter adapter(String url) {
        return new OllamaVisionModelAdapter(url, MODEL, DIGEST, Duration.ofSeconds(5), "5m");
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
