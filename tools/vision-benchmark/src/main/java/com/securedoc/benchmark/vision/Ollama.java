package com.securedoc.benchmark.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The few calls the harness makes to the local Ollama server. Only localhost is allowed. */
public final class Ollama {

    static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Ollama(String baseUrl) {
        URI uri = URI.create(baseUrl);
        String host = uri.getHost();
        if (!"localhost".equals(host) && !"127.0.0.1".equals(host)) {
            // Task 0033: models run locally; nothing is sent to an outside service.
            throw new IllegalArgumentException("The benchmark only talks to a local Ollama (localhost).");
        }
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public String baseUrl() {
        return baseUrl;
    }

    JsonNode post(String path, ObjectNode body, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            // The error body can echo the request; report the status only.
            throw new IOException("Ollama " + path + " returned HTTP " + response.statusCode());
        }
        return JSON.readTree(response.body());
    }

    JsonNode get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IOException("Ollama " + path + " returned HTTP " + response.statusCode());
        return JSON.readTree(response.body());
    }

    /** The exact model behind a name: digest, size, quantization, licence, capabilities. */
    public record ModelInfo(String name, String digest, String shortId, long sizeBytes, String family,
                            String parameterSize, String quantization, String licenceFirstLine,
                            List<String> capabilities, String version) {
        public boolean thinks() {
            return capabilities.contains("thinking");
        }
    }

    public ModelInfo modelInfo(String name) throws IOException, InterruptedException {
        JsonNode tags = get("/api/tags");
        JsonNode entry = null;
        for (JsonNode m : tags.path("models")) {
            if (name.equals(m.path("name").asText()) || name.equals(m.path("model").asText())) entry = m;
        }
        if (entry == null) throw new IOException("model not installed in Ollama: " + name);
        ObjectNode body = JSON.createObjectNode().put("model", name);
        JsonNode show = post("/api/show", body, Duration.ofSeconds(30));
        String licence = show.path("license").asText("").strip();
        String firstLine = licence.isEmpty() ? "(none in model)" : licence.lines().filter(l -> !l.isBlank()).findFirst().orElse("").strip();
        if (firstLine.length() > 120) firstLine = firstLine.substring(0, 120) + "...";
        List<String> caps = new ArrayList<>();
        show.path("capabilities").forEach(c -> caps.add(c.asText()));
        String digest = entry.path("digest").asText();
        JsonNode details = show.path("details");
        String version = get("/api/version").path("version").asText();
        return new ModelInfo(name, digest, digest.length() >= 12 ? digest.substring(0, 12) : digest,
                entry.path("size").asLong(), details.path("family").asText(), details.path("parameter_size").asText(),
                details.path("quantization_level").asText(), firstLine, caps, version);
    }

    /** Models in memory and how much GPU memory each holds. */
    public Map<String, Long> loadedVram() throws IOException, InterruptedException {
        Map<String, Long> out = new LinkedHashMap<>();
        for (JsonNode m : get("/api/ps").path("models")) {
            out.put(m.path("name").asText(), m.path("size_vram").asLong());
        }
        return out;
    }

    public void unload(String model) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode().put("model", model);
        body.put("keep_alive", 0);
        post("/api/generate", body, Duration.ofSeconds(60));
    }

    /** Loads a model (an empty request) and returns the seconds it took. */
    public double load(String model) throws IOException, InterruptedException {
        long t = System.nanoTime();
        ObjectNode body = JSON.createObjectNode().put("model", model).put("keep_alive", "30m");
        post("/api/generate", body, Duration.ofMinutes(5));
        return (System.nanoTime() - t) / 1e9;
    }
}
