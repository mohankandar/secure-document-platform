package com.securedoc.platform.api.config;

import com.securedoc.platform.ai.analysis.OllamaTextModelPin;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task 0110, hardening: text model pinned and local. */
class TextModelStartCheckTest {

    private static final String APPROVED = "a".repeat(64);
    private static final String OTHER = "b".repeat(64);

    private HttpServer server;
    private volatile String installedDigest = APPROVED;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] body = ("{\"models\":[{\"name\":\"text-model:1b\",\"digest\":\"sha256:" + installedDigest + "\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private TextModelStartCheck check(String baseUrl, String model, String digest) {
        AiRuntimeProperties properties = new AiRuntimeProperties();
        properties.setProvider("ollama");
        properties.setBaseUrl(baseUrl);
        properties.setModel(model);
        properties.setModelDigest(digest);
        return new TextModelStartCheck(properties);
    }

    @Test
    void theApprovedModelStarts() {
        assertThatCode(() -> check(url(), "text-model:1b", "sha256:" + APPROVED.toUpperCase()).check())
                .doesNotThrowAnyException();
    }

    @Test
    void anotherDigestRefusesTheStart() {
        installedDigest = OTHER;
        assertThatThrownBy(() -> check(url(), "text-model:1b", APPROVED).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LV-AI-008").hasMessageContaining("not the approved one");
    }

    @Test
    void aModelNotInstalledRefusesTheStart() {
        assertThatThrownBy(() -> check(url(), "missing-model:1b", APPROVED).check())
                .hasMessageContaining("LV-AI-008").hasMessageContaining("not installed");
    }

    @Test
    void noApprovedDigestRefusesTheStart() {
        assertThatThrownBy(() -> check(url(), "text-model:1b", "").check())
                .hasMessageContaining("LV-AI-008").hasMessageContaining("no approved digest");
        assertThatThrownBy(() -> check(url(), "text-model:1b", "latest").check())
                .hasMessageContaining("no approved digest");
    }

    @Test
    void aServerOnTheInternetRefusesTheStart() {
        assertThatThrownBy(() -> check("http://203.0.113.7:11434", "text-model:1b", APPROVED).check())
                .hasMessageContaining("LV-AI-006");
        assertThat(OllamaTextModelPin.check("http://203.0.113.7:11434", "text-model:1b", APPROVED).result())
                .isEqualTo(OllamaTextModelPin.Result.NOT_LOCAL);
    }

    @Test
    void aServerThatDoesNotAnswerIsLoggedAndTheStartGoesOn() {
        // Nothing listens on port 1 of this machine.
        assertThatCode(() -> check("http://127.0.0.1:1", "text-model:1b", APPROVED).check()).doesNotThrowAnyException();
    }

    @Test
    void anotherProviderIsNotChecked() {
        AiRuntimeProperties heuristic = new AiRuntimeProperties();
        heuristic.setProvider("heuristic");
        heuristic.setBaseUrl("http://203.0.113.7:11434");
        assertThatCode(() -> new TextModelStartCheck(heuristic).check()).doesNotThrowAnyException();
    }
}
