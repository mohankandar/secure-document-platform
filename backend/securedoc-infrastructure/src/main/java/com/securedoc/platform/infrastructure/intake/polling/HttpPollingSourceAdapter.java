package com.securedoc.platform.infrastructure.intake.polling;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.securedoc.platform.application.intake.command.UploadContent;
import com.securedoc.platform.application.intake.polling.*;
import com.securedoc.platform.application.port.out.PollingSourcePort;
import com.securedoc.platform.domain.integration.IntegrationAuthenticationType;
import com.securedoc.platform.domain.integration.IntegrationConnection;
import com.securedoc.platform.infrastructure.secrets.IntegrationSecrets;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/**
 * Standard LexaVault pull-source HTTP contract.
 * Paths are derived beneath the IT-configured base endpoint; source payloads cannot inject arbitrary URLs.
 */
@Component
public class HttpPollingSourceAdapter implements PollingSourcePort {
    private static final String CONTRACT_ROOT = "/lexavault/v1/pickups";
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Path stagingRoot;
    private final IntegrationSecrets secrets;
    /** Hardening (task 0110): the largest polling answer read (a list of cases or a receipt); PDFs have their own limit. */
    static final int DEFAULT_MAX_ANSWER_BYTES = 20 * 1024 * 1024;
    private final int maxAnswerBytes;

    @org.springframework.beans.factory.annotation.Autowired
    public HttpPollingSourceAdapter(ObjectMapper mapper, IntegrationSecrets secrets,
                                    @org.springframework.beans.factory.annotation.Value(
                                            "${lexavault.polling.max-answer-bytes:" + DEFAULT_MAX_ANSWER_BYTES + "}")
                                    int maxAnswerBytes) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), mapper,
                Path.of(System.getProperty("java.io.tmpdir"), "lexavault-polling-staging"), secrets, maxAnswerBytes);
    }

    HttpPollingSourceAdapter(HttpClient client, ObjectMapper mapper, Path stagingRoot, IntegrationSecrets secrets) {
        this(client, mapper, stagingRoot, secrets, DEFAULT_MAX_ANSWER_BYTES);
    }

    HttpPollingSourceAdapter(HttpClient client, ObjectMapper mapper, Path stagingRoot, IntegrationSecrets secrets,
                             int maxAnswerBytes) {
        if (maxAnswerBytes < 1) throw new IllegalArgumentException("lexavault.polling.max-answer-bytes must be positive");
        this.client = client; this.mapper = mapper; this.stagingRoot = stagingRoot; this.secrets = Objects.requireNonNull(secrets);
        this.maxAnswerBytes = maxAnswerBytes;
    }

    @Override
    public List<PollingSourcePackage> discoverAvailable(IntegrationConnection connection, int limit) {
        URI uri = resolve(connection, CONTRACT_ROOT + "?limit=" + Math.max(1, Math.min(limit, 1000)));
        HttpRequest request = authorized(connection, HttpRequest.newBuilder(uri).GET()).timeout(Duration.ofSeconds(connection.timeoutSeconds())).build();
        Answer response = answer(request, connection);
        require2xx(response.statusCode(), "POLL_DISCOVERY_FAILED");
        try {
            DiscoveryEnvelope envelope = mapper.readValue(response.body(), DiscoveryEnvelope.class);
            return envelope.items() == null ? List.of() : envelope.items().stream().map(this::toDomain).toList();
        } catch (Exception e) {
            throw technical("POLL_RESPONSE_INVALID", "Polling source returned an invalid discovery payload", e);
        }
    }

    @Override
    public RetrievedPollingDocument retrieveDocument(IntegrationConnection connection, PollingSourcePackage pkg,
                                                      PollingSourceDocument document) {
        String path = CONTRACT_ROOT + "/" + enc(pkg.externalSourcePackageId()) + "/revisions/" + enc(pkg.sourceRevision())
                + "/documents/" + enc(document.externalSourceDocumentId());
        HttpRequest request = authorized(connection, HttpRequest.newBuilder(resolve(connection, path)).GET())
                .timeout(Duration.ofSeconds(connection.timeoutSeconds())).build();
        HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream(), connection);
        require2xx(response.statusCode(), "RETRIEVAL_FAILED");
        String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream").split(";",2)[0].trim();
        if (!"application/pdf".equalsIgnoreCase(contentType)) {
            try { response.body().close(); } catch (IOException ignored) {}
            throw technical("RETRIEVAL_INVALID_CONTENT_TYPE", "Polling source document is not application/pdf", null);
        }
        try {
            Files.createDirectories(stagingRoot);
            Path temp = Files.createTempFile(stagingRoot, "poll-", ".pdf");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    total += read;
                    if (total > connection.maxPdfSizeBytes()) {
                        // Task 0102: its own code, so polling tells a file too big (final) from a staging fault.
                        throw technical("RETRIEVAL_TOO_LARGE", "Retrieved PDF exceeds configured size limit", null);
                    }
                    digest.update(buffer, 0, read); out.write(buffer, 0, read);
                }
            } catch (Exception e) {
                Files.deleteIfExists(temp);
                throw e;
            }
            String sha = HexFormat.of().formatHex(digest.digest());
            return new RetrievedPollingDocument("application/pdf", sha, new FileUploadContent(temp, total), () -> Files.deleteIfExists(temp));
        } catch (Exception e) {
            if (e instanceof RuntimeException runtime) throw runtime;
            throw technical("RETRIEVAL_STAGING_FAILED", "Unable to stage retrieved PDF safely", e);
        }
    }

    @Override
    public String acknowledgeReceived(IntegrationConnection connection, PollingSourcePackage pkg, int attemptNumber) {
        String path = CONTRACT_ROOT + "/" + enc(pkg.externalSourcePackageId()) + "/revisions/" + enc(pkg.sourceRevision()) + "/acknowledgement";
        String body;
        try { body = mapper.writeValueAsString(Map.of("status", "RECEIVED", "attemptNumber", attemptNumber)); }
        catch (Exception e) { throw technical("ACK_SERIALIZATION_FAILED", "Unable to create source acknowledgement", e); }
        HttpRequest request = authorized(connection, HttpRequest.newBuilder(resolve(connection, path)))
                .timeout(Duration.ofSeconds(connection.timeoutSeconds())).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        Answer response = answer(request, connection);
        require2xx(response.statusCode(), "SOURCE_ACKNOWLEDGEMENT_FAILED");
        if (response.body() == null || response.body().isBlank()) return null;
        try {
            Map<String,Object> value = mapper.readValue(response.body(), new TypeReference<>() {});
            Object ref = value.get("receiptReference"); return ref == null ? null : String.valueOf(ref);
        } catch (Exception ignored) { return null; }
    }

    private PollingSourcePackage toDomain(SourcePackage p) {
        return new PollingSourcePackage(p.externalSourcePackageId(), p.sourceRevision(), Boolean.TRUE.equals(p.retry()),
                p.businessIdentifiers(), p.metadata(), p.documents() == null ? List.of() : p.documents().stream()
                .map(d -> new PollingSourceDocument(d.externalSourceDocumentId(), d.fileName(), d.sha256(),
                        sentLevel(d.sensitivityLevel()))).toList(), sentLevel(p.caseSensitivityLevel()), sentLevel(p.priority()));
    }

    private HttpRequest.Builder authorized(IntegrationConnection c, HttpRequest.Builder builder) {
        IntegrationAuthenticationType type = c.authenticationType();
        if (type == IntegrationAuthenticationType.NONE) return builder;
        String secret = resolveSecret(c);
        return switch (type) {
            case BASIC -> builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
            case API_KEY -> builder.header("X-API-Key", secret);
            case JWT -> builder.header("Authorization", "Bearer " + secret);
            case OAUTH2_CLIENT_CREDENTIALS -> throw technical("POLL_AUTH_CONFIGURATION_UNSUPPORTED",
                    "OAuth2 client-credentials polling requires a provider-specific token configuration", null);
            case MTLS -> throw technical("POLL_AUTH_CONFIGURATION_UNSUPPORTED",
                    "mTLS polling requires deployment-managed client TLS configuration", null);
            case NONE -> builder;
        };
    }

    /** Hardening: secret references limited to approved names; the app's one rule and resolver (LV-CFG-003 when refused). */
    private String resolveSecret(IntegrationConnection c) {
        try {
            return secrets.read(c);
        } catch (IntegrationSecrets.NotRead e) {
            throw technical("POLL_AUTH_CONFIGURATION_ERROR", e.getMessage(), null);
        }
    }

    private URI resolve(IntegrationConnection c, String relativePath) {
        try {
            URI base = URI.create(c.endpoint());
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))) {
                throw new IllegalArgumentException("Polling endpoint must use HTTP or HTTPS");
            }
            // Hardening (task 0110): outbound addresses limited, checked again after the name is resolved.
            com.securedoc.platform.application.integration.OutboundAddressRule.check(c.endpoint(), c.allowedHosts())
                    .ifPresent(refusal -> { throw new AddressRefused(); });
            String baseText = c.endpoint().endsWith("/") ? c.endpoint().substring(0, c.endpoint().length()-1) : c.endpoint();
            return URI.create(baseText + relativePath);
        } catch (AddressRefused refused) {
            throw technical("POLL_CONFIGURATION_ERROR", "Polling endpoint is not allowed", null);
        } catch (RuntimeException e) { throw technical("POLL_CONFIGURATION_ERROR", "Polling endpoint is invalid", e); }
    }

    private static final class AddressRefused extends RuntimeException {
        AddressRefused() { super(null, null, false, false); }
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler, IntegrationConnection connection) {
        try { return client.send(req, handler); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw technical("POLL_INTERRUPTED", "Polling request was interrupted", e); }
        catch (IOException e) { throw technical("POLL_SOURCE_UNAVAILABLE", "Polling source is unavailable", e); }
    }
    private record Answer(int statusCode, String body) {}

    /** Hardening (task 0110): the answer is read up to the limit; a larger one is refused (LV-INT-045) and not kept. */
    private Answer answer(HttpRequest request, IntegrationConnection connection) {
        HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream(), connection);
        byte[] bytes;
        try (InputStream in = response.body()) {
            bytes = in.readNBytes(maxAnswerBytes + 1);
        } catch (IOException e) {
            throw technical("POLL_SOURCE_UNAVAILABLE", "Polling source is unavailable", e);
        }
        if (bytes.length > maxAnswerBytes) {
            throw technical("POLL_RESPONSE_TOO_LARGE", "Polling source answer exceeds the configured size limit", null);
        }
        return new Answer(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
    }
    private void require2xx(int status, String category) { if (status < 200 || status >= 300) throw technical(category, "Polling source returned HTTP " + status, null); }
    private RuntimeException technical(String category, String detail, Throwable cause) {
        return cause == null ? new PollingSourceException(category, detail) : new PollingSourceException(category, detail, cause);
    }
    private static String enc(String v){return URLEncoder.encode(Objects.requireNonNull(v), StandardCharsets.UTF_8).replace("+", "%20");}

    private record DiscoveryEnvelope(List<SourcePackage> items) {}
    private record SourcePackage(String externalSourcePackageId, String sourceRevision, Boolean retry,
                                 @tools.jackson.databind.annotation.JsonDeserialize(contentUsing = com.securedoc.platform.infrastructure.casefields.CaseFieldJson.class)
                                 Map<String,String> businessIdentifiers, Map<String,String> metadata, List<SourceDocument> documents,
                                 tools.jackson.databind.JsonNode caseSensitivityLevel,
                                 tools.jackson.databind.JsonNode priority) {}
    private record SourceDocument(String externalSourceDocumentId, String fileName, String sha256,
                                  tools.jackson.databind.JsonNode sensitivityLevel) {}

    /** Task 0021: a level as the source sent it; never refused here (an unknown one is taken as Standard later). */
    private static String sentLevel(tools.jackson.databind.JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        return value.isString() ? value.stringValue() : value.toString();
    }

    private static final class FileUploadContent implements UploadContent {
        private final Path path; private final long length;
        private FileUploadContent(Path path, long length){this.path=path;this.length=length;}
        @Override public InputStream openStream(){try{return Files.newInputStream(path);}catch(IOException e){throw new UncheckedIOException(e);}}
        @Override public long contentLength(){return length;}
    }

}
