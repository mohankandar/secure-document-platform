package com.securedoc.platform.ai.analysis;

import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisPolicy;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisRequest;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.PageContent;
import com.securedoc.platform.domain.finding.EntityType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0091: the same page gives the same request, byte for byte, also after a restart. The texts below are written out
 * because an order that changes at every start of Java is the same twice within one. Any change of them is an AI change
 * (the model change gate).
 */
class OllamaAnswerSchemaTest {
    private static final String FIND = "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"findings\":{\"type\":\"array\","
            + "\"items\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"pageNumber\":{\"type\":\"integer\",\"minimum\":1},"
            + "\"entityType\":{\"type\":\"string\",\"enum\":[\"SSN\",\"DATE_OF_BIRTH\",\"CREDIT_CARD_NUMBER\",\"CREDIT_SCORE\"]},"
            + "\"matchedText\":{\"type\":\"string\"},\"contextText\":{\"type\":\"string\"},"
            + "\"confidence\":{\"type\":\"number\",\"minimum\":0,\"maximum\":1}},"
            + "\"required\":[\"pageNumber\",\"entityType\",\"matchedText\",\"contextText\",\"confidence\"]}}},\"required\":[\"findings\"]}";
    private static final String NAMES = "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"findings\":{\"type\":\"array\","
            + "\"items\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"pageNumber\":{\"type\":\"integer\",\"minimum\":1},"
            + "\"entityType\":{\"type\":\"string\",\"enum\":[\"PERSON_NAME\"]},\"matchedText\":{\"type\":\"string\"},"
            + "\"confidence\":{\"type\":\"number\",\"minimum\":0,\"maximum\":1}},"
            + "\"required\":[\"pageNumber\",\"entityType\",\"matchedText\",\"confidence\"]}}},\"required\":[\"findings\"]}";

    @Test
    void twoAdaptersSendTheSameBytes() throws Exception {
        List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] answer = "{\"message\":{\"content\":\"{\\\"findings\\\":[]}\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        try {
            for (int i = 0; i < 2; i++) {
                new OllamaSensitiveContentAnalysisAdapter("http://127.0.0.1:" + server.getAddress().getPort(), "m",
                        Duration.ofSeconds(20), 1, 1, Duration.ZERO, "5m", true, false, 8192, 2048, 2, 14_000)
                        .analyze(new AnalysisRequest(null, List.of(new PageContent(1, "page")),
                                new AnalysisPolicy(Set.of(EntityType.SSN, EntityType.PERSON_NAME), true)));
            }
        } finally {
            server.stop(0);
        }
        assertEquals(4, bodies.size());
        assertEquals(bodies.get(0), bodies.get(2));
        assertEquals(bodies.get(1), bodies.get(3));
        assertTrue(bodies.get(0).contains("\"format\":" + FIND), bodies.get(0));
        assertTrue(bodies.get(1).contains("\"format\":" + NAMES), bodies.get(1));
        assertTrue(bodies.get(0).contains("\"messages\":[{\"role\":\"system\",\"content\":"), bodies.get(0));
    }
}
