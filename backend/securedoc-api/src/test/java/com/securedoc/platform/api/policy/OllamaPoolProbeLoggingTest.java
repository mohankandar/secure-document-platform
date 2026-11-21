package com.securedoc.platform.api.policy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.securedoc.platform.ai.analysis.OllamaInferencePoolSensitiveContentAnalysisAdapter;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.PageContent;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0023 C7 (#118): a failed node health probe is logged with the node id and a code. */
class OllamaPoolProbeLoggingTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void failedProbeIsLoggedWithNodeIdAndCodeButNoUrl() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] body = "{\"error\":\"secret internal detail\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();

        var pool = new OllamaInferencePoolSensitiveContentAnalysisAdapter(
                List.of(new OllamaInferencePoolSensitiveContentAnalysisAdapter.NodeDefinition("sick-node", baseUrl, 1)),
                "test-model", Duration.ofSeconds(2), 1, 1, Duration.ZERO, "5m", true, false, 0, 0, 1, 14_000,
                Duration.ofMillis(300), Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ZERO, 1);

        Logger logger = (Logger) LoggerFactory.getLogger(OllamaInferencePoolSensitiveContentAnalysisAdapter.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            assertThrows(TechnicalDependencyException.class,
                    () -> pool.analyze(List.of(new PageContent(1, "PAGE_ONE"))));
        } finally {
            logger.detachAppender(logs);
        }

        List<String> lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertTrue(lines.stream().anyMatch(line -> line.startsWith(
                "AI_INFERENCE_NODE_PROBE_FAILED provider=ollama model=test-model inferenceNodeId=sick-node "
                        + "reasonCode=AI_NODE_PROBE_HTTP_STATUS detail=HTTP_503")), lines.toString());
        assertFalse(String.join("\n", lines).contains("localhost:" + server.getAddress().getPort()));
        assertFalse(String.join("\n", lines).contains("secret internal detail"));
    }
}
