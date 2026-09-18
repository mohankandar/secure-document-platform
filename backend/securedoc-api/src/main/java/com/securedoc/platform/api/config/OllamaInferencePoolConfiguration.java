package com.securedoc.platform.api.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared parsing/validation for deployment-managed Ollama inference-pool endpoints. */
public final class OllamaInferencePoolConfiguration {

    private static final int MAX_NODES = 32;

    private OllamaInferencePoolConfiguration() {
    }

    /**
     * Parses comma-separated entries in either {@code http://host:port} or
     * {@code stable-node-id=http://host:port} form.
     */
    public static List<NodeTarget> parse(String configuredNodeUrls) {
        if (configuredNodeUrls == null || configuredNodeUrls.isBlank()) {
            throw new IllegalArgumentException(
                    "securedoc.ai.pool-node-urls must define at least one Ollama endpoint when pool-enabled=true");
        }

        List<NodeTarget> nodes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> urls = new HashSet<>();
        Set<String> endpointIdentities = new HashSet<>();
        int ordinal = 1;
        for (String rawEntry : configuredNodeUrls.split(",")) {
            String entry = rawEntry == null ? "" : rawEntry.trim();
            if (entry.isEmpty()) {
                continue;
            }

            String id;
            String url;
            if (entry.startsWith("http://") || entry.startsWith("https://")) {
                id = "ai-" + ordinal;
                url = entry;
            } else {
                int separator = entry.indexOf('=');
                if (separator <= 0 || separator == entry.length() - 1) {
                    throw new IllegalArgumentException("AI pool entry must be URL or stableId=URL: " + entry);
                }
                id = entry.substring(0, separator).trim();
                url = entry.substring(separator + 1).trim();
            }

            if (!id.matches("[A-Za-z0-9._-]{1,64}")) {
                throw new IllegalArgumentException("AI pool node id must match [A-Za-z0-9._-]{1,64}: " + id);
            }
            String normalizedUrl = normalizeUrl(url);
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate AI pool node id: " + id);
            }
            if (!urls.add(normalizedUrl)) {
                throw new IllegalArgumentException("Duplicate AI pool node URL configured for node: " + id);
            }
            String endpointIdentity = endpointIdentity(normalizedUrl);
            if (!endpointIdentities.add(endpointIdentity)) {
                throw new IllegalArgumentException(
                        "Duplicate AI pool endpoint identity configured for node " + id
                                + "; localhost/loopback aliases must not be treated as independent capacity");
            }
            nodes.add(new NodeTarget(id, normalizedUrl));
            ordinal++;
            if (nodes.size() > MAX_NODES) {
                throw new IllegalArgumentException("AI pool supports at most " + MAX_NODES + " configured nodes");
            }
        }

        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("securedoc.ai.pool-node-urls did not contain any usable endpoints");
        }
        return List.copyOf(nodes);
    }

    private static String normalizeUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AI pool node URL must not be blank");
        }
        String normalized = value.trim().replaceAll("/+$", "");
        URI uri = URI.create(normalized);
        String scheme = uri.getScheme();
        if (uri.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("AI pool node URL must be an absolute http/https URL: " + value);
        }
        return normalized;
    }


    private static String endpointIdentity(String normalizedUrl) {
        URI uri = URI.create(normalizedUrl);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (host.equals("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("0:0:0:0:0:0:0:1")) {
            host = "loopback";
        }
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        String path = uri.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            path = "";
        }
        return uri.getScheme().toLowerCase() + "://" + host + ":" + port + path;
    }

    public record NodeTarget(String id, String baseUrl) {
    }
}
