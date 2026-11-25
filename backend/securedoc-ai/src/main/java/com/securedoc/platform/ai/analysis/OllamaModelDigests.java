package com.securedoc.platform.ai.analysis;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;

/** Task 0064 (BL7): the digest an Ollama server states for an installed model. */
final class OllamaModelDigests {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final URI tags;
    private final HttpClient http;
    private final ObjectMapper json;

    OllamaModelDigests(String baseUrl, HttpClient http, ObjectMapper json) {
        this.tags = URI.create(baseUrl + "/api/tags");
        this.http = http;
        this.json = json;
    }

    /** Empty when the model is not installed. */
    Optional<String> of(String model) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(tags).timeout(TIMEOUT).GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException busy) {
            // Task 0080: a server busy with an answer can be late once; a page is not unread for that.
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() != 200) throw new IOException("The model list is not available");
        String wanted = withTag(model);
        try {
            for (JsonNode node : json.readTree(response.body()).path("models")) {
                if (wanted.equals(withTag(node.path("name").asString("")))
                        || wanted.equals(withTag(node.path("model").asString("")))) {
                    String digest = node.path("digest").asString("").trim().toLowerCase(java.util.Locale.ROOT);
                    return Optional.of(digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : digest);
                }
            }
        } catch (JacksonException e) {
            throw new IOException("The model list could not be read");
        }
        return Optional.empty();
    }

    private static String withTag(String name) {
        return name.indexOf(':', name.lastIndexOf('/') + 1) >= 0 ? name : name + ":latest";
    }
}
