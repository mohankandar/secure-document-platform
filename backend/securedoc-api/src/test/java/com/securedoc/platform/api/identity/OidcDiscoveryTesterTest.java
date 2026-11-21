package com.securedoc.platform.api.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0023 B6 (#241): the stored and shown test message is a short category, never remote or library text. */
class OidcDiscoveryTesterTest {
    private static final String ISSUER = "https://login.example.test/realms/a";
    private static final String SECRET = "internal-10.1.2.3-secret-body";

    private static OidcDiscoveryTester.TestResult run(OidcDiscoveryTester.DiscoveryFetcher fetcher) {
        return new OidcDiscoveryTester(new ObjectMapper(), fetcher).test(ISSUER);
    }

    private static OidcDiscoveryTester.DiscoveryFetcher answering(int status, String body) {
        return discovery -> response(status, body);
    }

    @Test
    void networkFailuresSayCouldNotReachOrTimedOut() {
        var unreachable = run(d -> { throw new ConnectException("Connection refused: " + SECRET); });
        assertFalse(unreachable.passed());
        assertEquals("OIDC discovery could not reach the identity provider.", unreachable.message());

        var slow = run(d -> { throw new HttpTimeoutException("request timed out " + SECRET); });
        assertEquals("OIDC discovery timed out.", slow.message());

        var io = run(d -> { throw new IOException(SECRET); });
        assertEquals("OIDC discovery could not reach the identity provider.", io.message());
    }

    @Test
    void aNonOkStatusShowsOnlyTheStatus() {
        assertEquals("OIDC discovery returned HTTP 502.", run(answering(502, SECRET)).message());
    }

    @Test
    void aBodyThatIsNotJsonSaysSoWithoutQuotingIt() {
        var result = run(answering(200, "<html>" + SECRET + "</html>"));
        assertEquals("OIDC discovery did not return valid JSON.", result.message());
        assertEquals("OIDC discovery did not return valid JSON.", run(answering(200, "")).message());
        assertEquals("OIDC discovery did not return valid JSON.", run(answering(200, "[1,2]")).message());
    }

    @Test
    void contentProblemsNameTheFieldNotTheValue() {
        assertEquals("OIDC discovery is missing issuer.", run(answering(200, "{}")).message());
        assertEquals("OIDC discovery issuer does not match the configured issuer.",
                run(answering(200, "{\"issuer\":\"https://other.example/" + SECRET + "\"}")).message());
        assertEquals("OIDC discovery issuer does not match the configured issuer.",
                run(answering(200, "{\"issuer\":\"ht tp://" + SECRET + "\"}")).message());
        String badEndpoint = "{\"issuer\":\"" + ISSUER + "\",\"authorization_endpoint\":\"not a uri " + SECRET
                + "\",\"token_endpoint\":\"https://x/t\",\"jwks_uri\":\"https://x/j\"}";
        assertEquals("OIDC discovery contains an invalid endpoint.", run(answering(200, badEndpoint)).message());
    }

    @Test
    void aValidDocumentPasses() {
        String ok = "{\"issuer\":\"" + ISSUER + "\",\"authorization_endpoint\":\"https://x/a\","
                + "\"token_endpoint\":\"https://x/t\",\"jwks_uri\":\"https://x/j\"}";
        assertTrue(run(answering(200, ok)).passed());
    }

    @Test
    void anIssuerThatIsNotAUriIsNotQuoted() {
        var result = new OidcDiscoveryTester(new ObjectMapper(), answering(200, "{}")).test("https://bad host/" + SECRET);
        assertEquals("Invalid OIDC configuration: Issuer is not a valid URI.", result.message());
        assertEquals("Invalid OIDC configuration: Issuer must not contain credentials, query parameters, or fragments.",
                new OidcDiscoveryTester(new ObjectMapper(), answering(200, "{}"))
                        .test("https://user:pw@login.example.test").message());
    }

    @Test
    void noResultMessageEverHoldsRemoteText() {
        for (var fetcher : java.util.List.<OidcDiscoveryTester.DiscoveryFetcher>of(
                d -> { throw new IOException(SECRET); },
                d -> { throw new IllegalStateException(SECRET); },
                answering(500, SECRET), answering(200, SECRET))) {
            assertFalse(run(fetcher).message().contains(SECRET));
        }
    }

    private static HttpResponse<String> response(int status, String body) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return HttpRequest.newBuilder(URI.create(ISSUER)).build(); }
            @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
            @Override public String body() { return body; }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return URI.create(ISSUER); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
