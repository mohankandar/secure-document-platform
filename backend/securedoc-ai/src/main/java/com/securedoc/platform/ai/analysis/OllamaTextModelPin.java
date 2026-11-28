package com.securedoc.platform.ai.analysis;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Task 0110 (hardening: text model pinned and local). The same rule as the vision model: the server must be local and
 * its copy of the model must have exactly the approved digest.
 */
public final class OllamaTextModelPin {

    public enum Result { APPROVED, NOT_LOCAL, NOT_INSTALLED, DIFFERENT, NOT_REACHABLE }

    /** What the server said, and the digest it stated (empty when it stated none). */
    public record Check(Result result, String installedDigest) {
    }

    private OllamaTextModelPin() {
    }

    /** The approved digest as 64 lowercase hexadecimal characters; refused when it is anything else. */
    public static String normalized(String digest) {
        String pinned = digest == null ? "" : digest.trim().toLowerCase(Locale.ROOT).replaceFirst("^sha256:", "");
        if (!pinned.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("The text model's digest must be its 64 hexadecimal characters");
        }
        return pinned;
    }

    public static Check check(String baseUrl, String model, String approvedDigest) {
        String approved = normalized(approvedDigest);
        String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        try {
            if (!LocalModelServer.isLocal(URI.create(base + "/").getHost())) return new Check(Result.NOT_LOCAL, "");
        } catch (IOException | IllegalArgumentException unknown) {
            return new Check(Result.NOT_LOCAL, "");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        Optional<String> installed;
        try {
            installed = new OllamaModelDigests(base, http, JsonMapper.builderWithJackson2Defaults().build())
                    .of(model.trim());
        } catch (IOException e) {
            return new Check(Result.NOT_REACHABLE, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Check(Result.NOT_REACHABLE, "");
        }
        if (installed.isEmpty()) return new Check(Result.NOT_INSTALLED, "");
        return new Check(installed.get().equals(approved) ? Result.APPROVED : Result.DIFFERENT, installed.get());
    }
}
