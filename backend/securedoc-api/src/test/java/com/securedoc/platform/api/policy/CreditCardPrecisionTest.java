package com.securedoc.platform.api.policy;

import com.securedoc.platform.ai.analysis.OllamaSensitiveContentAnalysisAdapter;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisOutput;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.PageContent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreditCardPrecisionTest {

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
    void rejectsContractAmountsDatesLabelsAndGenericFinancialAccountAsCreditCard() throws Exception {
        String pageText = "Contract Number: 00028-2600093 | Total Sale Price $47,965.50 | "
                + "Payment Date 05-31-2026 | Credit Card # | Member 00204119401 | 7777777777777";

        startServer("""
                {"findings":[
                  {"entityType":"CREDIT_CARD_NUMBER","matchedText":"00028-2600093","contextText":"Contract Number: 00028-2600093","confidence":0.99},
                  {"entityType":"CREDIT_CARD_NUMBER","matchedText":"$47,965.50","contextText":"Total Sale Price $47,965.50","confidence":0.99},
                  {"entityType":"CREDIT_CARD_NUMBER","matchedText":"05-31-2026","contextText":"Payment Date 05-31-2026","confidence":0.99},
                  {"entityType":"CREDIT_CARD_NUMBER","matchedText":"Credit Card #","contextText":"Credit Card #","confidence":0.99},
                  {"entityType":"FINANCIAL_ACCOUNT","matchedText":"00204119401","contextText":"Member 00204119401","confidence":0.99},
                  {"entityType":"CREDIT_CARD_NUMBER","matchedText":"7777777777777","contextText":"7777777777777","confidence":0.99}
                ]}
                """);

        AnalysisOutput output = adapter().analyze(List.of(new PageContent(1, pageText)));

        assertEquals(0, output.findings().size());
    }

    @Test
    void acceptsActualPanShape() throws Exception {
        String pageText = "Credit Card Number: 4111 1111 1111 1111";
        startServer("""
                {"findings":[
                  {"entityType":"CREDIT_CARD_NUMBER","matchedText":"4111 1111 1111 1111","contextText":"Credit Card Number: 4111 1111 1111 1111","confidence":0.98}
                ]}
                """);

        AnalysisOutput output = adapter().analyze(List.of(new PageContent(1, pageText)));

        assertEquals(1, output.findings().size());
        assertEquals("CREDIT_CARD_NUMBER", output.findings().get(0).entityType());
        assertEquals("4111 1111 1111 1111", output.findings().get(0).matchedText());
    }

    @Test
    void doesNotMakeLuhnAProviderDiscoveryGate() throws Exception {
        String pageText = "Credit Card Number: 4111 1111 1111 1112";
        startServer("""
                {"findings":[
                  {"entityType":"CREDIT_CARD_NUMBER","matchedText":"4111 1111 1111 1112","contextText":"Credit Card Number: 4111 1111 1111 1112","confidence":0.80}
                ]}
                """);

        AnalysisOutput output = adapter().analyze(List.of(new PageContent(1, pageText)));

        assertEquals(1, output.findings().size(),
                "adapter shape guardrail must not replace downstream deterministic Luhn evidence");
    }

    private OllamaSensitiveContentAnalysisAdapter adapter() {
        return new OllamaSensitiveContentAnalysisAdapter(
                "http://localhost:" + server.getAddress().getPort(),
                "test-model",
                Duration.ofSeconds(5),
                1,
                1,
                Duration.ZERO);
    }

    private void startServer(String modelContent) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext("/api/chat", exchange -> sendModelResponse(exchange, modelContent));
        server.start();
    }

    private void sendModelResponse(HttpExchange exchange, String modelContent) throws IOException {
        String escaped = modelContent
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
        String envelope = "{\"message\":{\"content\":\"" + escaped + "\"}}";
        byte[] bytes = envelope.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
