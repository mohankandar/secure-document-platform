package com.securedoc.platform.api.identity;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Tests a draft login system's OIDC discovery document.
 *
 * <p>Task 0023 B6 (#241): the result message (stored as the connection's last test message and shown to IT) is one
 * of a fixed set of short categories: "could not reach", "timed out", "HTTP &lt;status&gt;", "not valid JSON",
 * "issuer does not match", "missing &lt;field&gt;", "invalid endpoint", or LexaVault's own issuer rules. Raw text from
 * the HTTP client, the JSON parser or the remote body is never stored, shown or logged: only the exception type.
 */
@Component
public class OidcDiscoveryTester {
    private static final Logger log = LoggerFactory.getLogger(OidcDiscoveryTester.class);

    static final String COULD_NOT_REACH = "OIDC discovery could not reach the identity provider.";
    static final String TIMED_OUT = "OIDC discovery timed out.";
    static final String NOT_JSON = "OIDC discovery did not return valid JSON.";
    static final String ISSUER_MISMATCH = "OIDC discovery issuer does not match the configured issuer.";
    static final String INVALID_ENDPOINT = "OIDC discovery contains an invalid endpoint.";
    static final String NOT_VERIFIED = "OIDC discovery could not be verified.";
    static final String INTERRUPTED = "OIDC connection test was interrupted.";

    /** Fetches the discovery document; replaceable in tests. */
    interface DiscoveryFetcher {
        HttpResponse<String> fetch(URI discovery) throws IOException, InterruptedException;
    }

    private final ObjectMapper mapper;
    private final DiscoveryFetcher fetcher;

    @Autowired
    public OidcDiscoveryTester(ObjectMapper mapper) {
        this(mapper, httpFetcher());
    }

    OidcDiscoveryTester(ObjectMapper mapper, DiscoveryFetcher fetcher) {
        this.mapper = mapper;
        this.fetcher = fetcher;
    }

    private static DiscoveryFetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return discovery -> client.send(HttpRequest.newBuilder(discovery).timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    public TestResult test(String issuerValue) {
        try {
            URI issuer = validateIssuer(issuerValue);
            URI discovery = URI.create(normalize(issuer) + "/.well-known/openid-configuration");
            HttpResponse<String> response = fetcher.fetch(discovery);
            if (response.statusCode() != 200) {
                return failed("HTTP_STATUS", null, "OIDC discovery returned HTTP " + response.statusCode() + ".");
            }
            JsonNode json = mapper.readTree(response.body() == null ? "" : response.body());
            if (json == null || !json.isObject()) return failed("NOT_JSON", null, NOT_JSON);
            String discoveredIssuer = text(json, "issuer");
            if (!normalize(issuer).equals(normalizeDiscovered(discoveredIssuer))) {
                return failed("ISSUER_MISMATCH", null, ISSUER_MISMATCH);
            }
            validateEndpoint(text(json, "authorization_endpoint"));
            validateEndpoint(text(json, "token_endpoint"));
            validateEndpoint(text(json, "jwks_uri"));
            return new TestResult(true, "OIDC discovery and required endpoints verified.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failed("INTERRUPTED", ex, INTERRUPTED);
        } catch (Refusal refusal) {
            return failed(refusal.category, null, refusal.getMessage());
        } catch (HttpTimeoutException ex) {
            return failed("TIMED_OUT", ex, TIMED_OUT);
        } catch (JacksonException ex) {
            return failed("NOT_JSON", ex, NOT_JSON);
        } catch (IOException ex) {
            return failed("COULD_NOT_REACH", ex, COULD_NOT_REACH);
        } catch (RuntimeException ex) {
            return failed("NOT_VERIFIED", ex, NOT_VERIFIED);
        }
    }

    private static TestResult failed(String category, Exception cause, String message) {
        log.warn("OIDC_DISCOVERY_TEST_FAILED category={} type={}", category,
                cause == null ? "none" : cause.getClass().getSimpleName());
        return new TestResult(false, message);
    }

    /** One of LexaVault's own fixed messages; never carries remote or entered text. */
    private static final class Refusal extends RuntimeException {
        private final String category;

        Refusal(String category, String message) {
            super(message, null, false, false);
            this.category = category;
        }
    }

    private static Refusal invalidConfiguration(String rule) {
        return new Refusal("INVALID_CONFIGURATION", "Invalid OIDC configuration: " + rule);
    }

    private static URI validateIssuer(String value) {
        if (value == null || value.isBlank()) throw invalidConfiguration("Issuer is required.");
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException notAUri) {
            throw invalidConfiguration("Issuer is not a valid URI.");
        }
        if (uri.getScheme() == null || uri.getHost() == null) throw invalidConfiguration("Issuer must be an absolute HTTP(S) URI.");
        if (!uri.getScheme().equalsIgnoreCase("https") && !uri.getScheme().equalsIgnoreCase("http")) {
            throw invalidConfiguration("Issuer must use HTTP or HTTPS.");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw invalidConfiguration("Issuer must not contain credentials, query parameters, or fragments.");
        }
        if (uri.getScheme().equalsIgnoreCase("http") && !isLoopback(uri.getHost())) {
            throw invalidConfiguration("HTTP issuers are allowed only for loopback local development.");
        }
        return uri;
    }

    private static void validateEndpoint(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException notAUri) {
            throw new Refusal("INVALID_ENDPOINT", INVALID_ENDPOINT);
        }
        if (!uri.isAbsolute() || (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))) {
            throw new Refusal("INVALID_ENDPOINT", INVALID_ENDPOINT);
        }
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new Refusal("MISSING_FIELD", "OIDC discovery is missing " + field + ".");
        }
        return value.asString();
    }

    private static String normalizeDiscovered(String discoveredIssuer) {
        try {
            return normalize(URI.create(discoveredIssuer));
        } catch (IllegalArgumentException notAUri) {
            throw new Refusal("ISSUER_MISMATCH", ISSUER_MISMATCH);
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
    }

    private static String normalize(URI uri) {
        String s = uri.toString().trim();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public record TestResult(boolean passed, String message) {}
}
