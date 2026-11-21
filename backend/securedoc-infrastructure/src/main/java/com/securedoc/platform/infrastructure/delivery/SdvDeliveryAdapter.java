package com.securedoc.platform.infrastructure.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.application.port.out.SdvConnectionTestPort;
import com.securedoc.platform.domain.integration.IntegrationAuthenticationType;
import com.securedoc.platform.domain.integration.IntegrationConnection;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Versioned LexaVault -> SDV master-delivery client. SDV owns durable vault storage; LexaVault
 * considers the business export complete only after the SDV submission reports COMPLETE.
 */
@Component
@ConditionalOnProperty(name = "securedoc.delivery.type", havingValue = "sdv")
public class SdvDeliveryAdapter implements DeliveryPort, SdvConnectionTestPort {
    private static final String DESTINATION_TYPE = "sdv";
    private static final String SUBMISSIONS_PATH = "/api/v1/submissions";
    /** Vero's upload header for a document's own level (Vero task 0024). */
    static final String SENSITIVITY_HEADER = "X-SDV-Sensitivity-Level";
    /** Vero's error codes for a refused sensitivity level start with this (docs/api/error-codes.md in Vero). */
    private static final String SDV_SENSITIVITY_CODE_PREFIX = "SDV-SEN-";

    private final IntegrationConnectionRepository connections;
    private final ObjectMapper mapper;
    private final HttpClient client;

    /** Task 0027: with export jobs on, one attempt per call; the export job spaces the tries. */
    private final boolean singleAttempt;

    @org.springframework.beans.factory.annotation.Autowired
    public SdvDeliveryAdapter(IntegrationConnectionRepository connections, ObjectMapper mapper,
                              @org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-mode:inline}") String exportMode) {
        this(connections, mapper, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                com.securedoc.platform.application.pipeline.ExportMode.parse(exportMode)
                        == com.securedoc.platform.application.pipeline.ExportMode.JOB);
    }

    public SdvDeliveryAdapter(IntegrationConnectionRepository connections, ObjectMapper mapper) {
        this(connections, mapper, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
    }

    SdvDeliveryAdapter(IntegrationConnectionRepository connections, ObjectMapper mapper, HttpClient client) {
        this(connections, mapper, client, false);
    }

    SdvDeliveryAdapter(IntegrationConnectionRepository connections, ObjectMapper mapper, HttpClient client,
                       boolean singleAttempt) {
        this.connections = Objects.requireNonNull(connections);
        this.mapper = Objects.requireNonNull(mapper);
        this.client = Objects.requireNonNull(client);
        this.singleAttempt = singleAttempt;
    }

    @Override
    public DeliveryDescriptor describe(TenantId tenantId) {
        IntegrationConnection c = connection(tenantId);
        return new DeliveryDescriptor(DESTINATION_TYPE, c.id(), c.configurationVersion());
    }

    /** Safe provider probe used by IT administration. No submission or customer PDF is created. */
    @Override
    public void testConnection(IntegrationConnection c) {
        if (c == null || !c.enabled() || c.purpose() != com.securedoc.platform.domain.integration.IntegrationPurpose.SDV_DELIVERY) {
            throw failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false,
                    "Integration connection is not an enabled SDV delivery destination", null);
        }
        HttpRequest request = authorized(c, HttpRequest.newBuilder(resolve(c, "/api/v1/health")).GET())
                .timeout(Duration.ofSeconds(c.timeoutSeconds())).build();
        HttpResponse<String> response = send(c, request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        require2xx(response.statusCode(), response.statusCode() >= 500 || response.statusCode() == 429,
                "SDV connection health check failed");
    }

    @Override
    public BatchDeliveryReference deliverBatch(DeliveryBatch batch) {
        IntegrationConnection c = connection(batch.tenantId());
        try {
            String sdvSubmissionId = batch.existingDestinationSubmissionReference();
            if (sdvSubmissionId == null) {
                sdvSubmissionId = withRetry(c, () -> createSubmission(c, batch));
                batch.progressListener().submissionAccepted(sdvSubmissionId);
            }

            final String activeSubmissionId = sdvSubmissionId;
            List<DeliveryReference> references = new ArrayList<>();
            boolean alreadyReceived = false;
            for (DeliveryItem item : batch.items()) {
                Upload upload = uploadDocument(c, activeSubmissionId, batch.sourceSubmissionId(), item);
                references.add(upload.reference());
                alreadyReceived |= upload.alreadyReceived();
                batch.progressListener().documentAcknowledged(upload.reference());
            }

            SubmissionStatus status = withRetry(c, () -> readSubmissionStatus(c, activeSubmissionId));
            if (!"COMPLETE".equalsIgnoreCase(status.status())) {
                throw failure(TechnicalFailureCode.DELIVERY_IO, true,
                        "SDV submission has not acknowledged all expected documents", null).withLvCode(com.securedoc.platform.domain.error.LvErrorCode.DLV_008);
            }
            connections.save(c.recordOperationalSuccess(java.time.Instant.now()));
            return new BatchDeliveryReference(DESTINATION_TYPE, activeSubmissionId, references, alreadyReceived);
        } catch (TechnicalDependencyException e) {
            connections.save(c.recordOperationalFailure(e.failureCode().name(), java.time.Instant.now()));
            throw e;
        }
    }

    private String createSubmission(IntegrationConnection c, DeliveryBatch batch) {
        List<Map<String,Object>> docs = batch.items().stream().map(item -> {
            Map<String,Object> d = new LinkedHashMap<>();
            d.put("sourceDocumentId", item.sourceDocumentId());
            d.put("fileName", item.originalFileName());
            d.put("contentType", item.mimeType());
            d.put("sizeBytes", item.sizeBytes());
            if (item.checksumValue() != null) d.put("sha256", item.checksumValue());
            return d;
        }).toList();
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("sourceSubmissionId", batch.sourceSubmissionId());
        body.put("sourceRevision", Integer.toString(batch.exportCycle()));
        body.put("businessIdentifiers", batch.businessIdentifiers());
        body.put("expectedDocumentCount", docs.size());
        body.put("documents", docs);
        // Task 0021 (Vero task 0024): the case (record) level goes in the submission; each document's own level goes
        // on its upload as X-SDV-Sensitivity-Level. Both are passed on exactly as the source system set them.
        body.put("recordSensitivityLevel", batch.caseSensitivityLevel().name());
        try {
            byte[] bytes = mapper.writeValueAsBytes(body);
            HttpRequest request = authorized(c, HttpRequest.newBuilder(resolve(c, SUBMISSIONS_PATH)))
                    .timeout(Duration.ofSeconds(c.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", batch.sourceSubmissionId())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
            HttpResponse<String> response = send(c, request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            refuseIfSensitivityRefused(response, "SDV refused the case sensitivity level");
            require2xx(response.statusCode(), false, "SDV submission creation failed");
            JsonNode json = mapper.readTree(response.body());
            String id = text(json, "submissionId");
            if (id == null) throw failure(TechnicalFailureCode.DELIVERY_IO, true,
                    "SDV submission response did not include submissionId", null).withLvCode(com.securedoc.platform.domain.error.LvErrorCode.DLV_007);
            return id;
        } catch (TechnicalDependencyException e) { throw e; }
        catch (Exception e) { throw failure(TechnicalFailureCode.DELIVERY_IO, true, "SDV submission creation failed", e).withLvCode(com.securedoc.platform.domain.error.LvErrorCode.DLV_007); }
    }

    /**
     * Task 0027: Vero answers a retry of the same upload (same key) with 200 and {@code idempotentReplay: true}, and a
     * file it already stores with 200 and {@code receiptAcknowledgement: DUPLICATE_ALREADY_STORED} /
     * {@code duplicate: true} (Vero docs/api/README.md). Both are a success; the export job audits them as
     * LV-DLV-009. Vero's 409 is never "already received": it means the same key with different content (SDV-INT-011,
     * SDV-SEN-003/004), so it stays LV-DLV-004 (or LV-DLV-003 for a level).
     */
    static boolean alreadyReceived(JsonNode json) {
        if (json == null) return false;
        JsonNode replay = json.get("idempotentReplay");
        JsonNode duplicate = json.get("duplicate");
        JsonNode receipt = json.get("receiptAcknowledgement");
        return (replay != null && replay.asBoolean(false))
                || (duplicate != null && duplicate.asBoolean(false))
                || (receipt != null && "DUPLICATE_ALREADY_STORED".equals(receipt.asText()));
    }

    private record Upload(DeliveryReference reference, boolean alreadyReceived) { }

    private Upload uploadDocument(IntegrationConnection c, String sdvSubmissionId,
                                  String sourceSubmissionId, DeliveryItem item) {
        String path = SUBMISSIONS_PATH + "/" + enc(sdvSubmissionId) + "/documents";
        return withRetry(c, () -> {
            HttpRequest.Builder builder = authorized(c, HttpRequest.newBuilder(resolve(c, path)))
                    .timeout(Duration.ofSeconds(c.timeoutSeconds()))
                    .header("Content-Type", item.mimeType())
                    .header("Idempotency-Key", sourceSubmissionId + ":" + item.sourceDocumentId())
                    .header("X-Source-Document-Id", item.sourceDocumentId())
                    .header("X-Original-File-Name", safeHeader(item.originalFileName()))
                    .header(SENSITIVITY_HEADER, item.sensitivityLevel().name());
            if (item.checksumValue() != null) builder.header("X-Content-SHA256", item.checksumValue());
            HttpResponse<String> response = send(c, builder.POST(HttpRequest.BodyPublishers.ofInputStream(item.contentSource()::open)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            refuseIfSensitivityRefused(response, "SDV refused the document sensitivity level");
            require2xx(response.statusCode(), response.statusCode() >= 500 || response.statusCode() == 429,
                    "SDV document upload failed");
            try {
                JsonNode json = mapper.readTree(response.body());
                String remoteDocumentId = text(json, "documentId");
                String acknowledgedSourceId = text(json, "sourceDocumentId");
                if (acknowledgedSourceId != null && !item.sourceDocumentId().equals(acknowledgedSourceId)) {
                    throw failure(TechnicalFailureCode.DELIVERY_IO, false,
                            "SDV acknowledgement source document id mismatch", null).withLvCode(com.securedoc.platform.domain.error.LvErrorCode.DLV_007);
                }
                return new Upload(new DeliveryReference(item.documentId(), item.sourceDocumentId(), DESTINATION_TYPE,
                        remoteDocumentId, "sdv://" + sdvSubmissionId + "/" + (remoteDocumentId == null ? item.sourceDocumentId() : remoteDocumentId)),
                        alreadyReceived(json));
            } catch (TechnicalDependencyException e) { throw e; }
            catch (Exception e) { throw failure(TechnicalFailureCode.DELIVERY_IO, true, "SDV document acknowledgement was invalid", e).withLvCode(com.securedoc.platform.domain.error.LvErrorCode.DLV_007); }
        });
    }

    private SubmissionStatus readSubmissionStatus(IntegrationConnection c, String sdvSubmissionId) {
        String path = SUBMISSIONS_PATH + "/" + enc(sdvSubmissionId);
        HttpRequest request = authorized(c, HttpRequest.newBuilder(resolve(c, path)).GET())
                .timeout(Duration.ofSeconds(c.timeoutSeconds())).build();
        HttpResponse<String> response = send(c, request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        require2xx(response.statusCode(), true, "SDV submission status check failed");
        try {
            JsonNode json = mapper.readTree(response.body());
            return new SubmissionStatus(text(json, "status"));
        } catch (Exception e) {
            throw failure(TechnicalFailureCode.DELIVERY_IO, true, "SDV submission status response was invalid", e).withLvCode(com.securedoc.platform.domain.error.LvErrorCode.DLV_007);
        }
    }

    private IntegrationConnection connection(TenantId tenantId) {
        IntegrationConnection c = connections.findEnabledSdvDeliveryByTenantId(tenantId)
                .orElseThrow(() -> failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false,
                        "No enabled SDV delivery connection is configured for this tenant", null));
        if (!c.enabled()) throw failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false, "SDV delivery connection is disabled", null);
        return c;
    }

    private HttpRequest.Builder authorized(IntegrationConnection c, HttpRequest.Builder builder) {
        IntegrationAuthenticationType type = c.authenticationType();
        if (type == IntegrationAuthenticationType.NONE) return builder;
        String secret = resolveSecret(c.secretReference());
        return switch (type) {
            case BASIC -> builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
            case API_KEY -> builder.header("X-API-Key", secret);
            case JWT -> builder.header("Authorization", "Bearer " + secret);
            case OAUTH2_CLIENT_CREDENTIALS -> throw failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false,
                    "SDV OAuth2 client-credentials requires provider-specific token configuration", null).withLvCode(com.securedoc.platform.domain.error.LvErrorCode.DLV_005);
            case MTLS -> throw failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false,
                    "SDV mTLS requires deployment-managed client TLS configuration", null).withLvCode(com.securedoc.platform.domain.error.LvErrorCode.DLV_005);
            case NONE -> builder;
        };
    }

    private String resolveSecret(String ref) {
        if (ref == null || ref.isBlank()) throw failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false, "Protected SDV secret reference is missing", null).withLvCode(com.securedoc.platform.domain.error.LvErrorCode.DLV_005);
        if (ref.startsWith("env:")) {
            String value = System.getenv(ref.substring(4));
            if (value == null || value.isBlank()) throw failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false, "Configured SDV environment secret is unavailable", null).withLvCode(com.securedoc.platform.domain.error.LvErrorCode.DLV_005);
            return value;
        }
        if (ref.startsWith("sys:")) {
            String value = System.getProperty(ref.substring(4));
            if (value == null || value.isBlank()) throw failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false, "Configured SDV system-property secret is unavailable", null).withLvCode(com.securedoc.platform.domain.error.LvErrorCode.DLV_005);
            return value;
        }
        throw failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false, "Unsupported SDV protected secret reference provider", null).withLvCode(com.securedoc.platform.domain.error.LvErrorCode.DLV_005);
    }

    private URI resolve(IntegrationConnection c, String path) {
        try {
            URI base = URI.create(c.endpoint());
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))) {
                throw new IllegalArgumentException("SDV endpoint must use HTTP or HTTPS");
            }
            String root = c.endpoint().endsWith("/") ? c.endpoint().substring(0, c.endpoint().length()-1) : c.endpoint();
            return URI.create(root + path);
        } catch (RuntimeException e) {
            throw failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false, "SDV endpoint is invalid", e);
        }
    }

    private <T> HttpResponse<T> send(IntegrationConnection c, HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try { return client.send(request, handler); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw failure(TechnicalFailureCode.DELIVERY_IO, true, "SDV delivery was interrupted", e); }
        catch (IOException e) { throw failure(TechnicalFailureCode.DELIVERY_IO, true, "SDV is unavailable", e); }
    }

    private <T> T withRetry(IntegrationConnection c, java.util.function.Supplier<T> operation) {
        TechnicalDependencyException last = null;
        int attempts = singleAttempt ? 1 : Math.max(1, c.retryMaxAttempts() + 1);
        for (int i = 1; i <= attempts; i++) {
            try { return operation.get(); }
            catch (TechnicalDependencyException e) {
                last = e;
                if (!e.retryable() || i == attempts) throw e.withAttempts(i);
                try { Thread.sleep(Math.min(1000L * i, 3000L)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw e.withAttempts(i); }
            }
        }
        throw last == null ? failure(TechnicalFailureCode.DELIVERY_IO, true, "SDV delivery failed", null) : last;
    }

    /**
     * Task 0021: Vero answers a level it will not take with a 4xx whose {@code code} is SDV-SEN-nnn. That is
     * LV-DLV-003, "Destination refused the sensitivity level": never retried, because LexaVault would only send the
     * same level again.
     */
    private void refuseIfSensitivityRefused(HttpResponse<String> response, String message) {
        int status = response.statusCode();
        if (status < 400 || status >= 500 || status == 429) return;
        String code;
        try {
            code = text(mapper.readTree(response.body() == null ? "" : response.body()), "code");
        } catch (Exception unreadable) {
            return;
        }
        if (code != null && code.startsWith(SDV_SENSITIVITY_CODE_PREFIX)) {
            throw failure(TechnicalFailureCode.DELIVERY_SENSITIVITY_REFUSED, false, message, null);
        }
    }

    private void require2xx(int status, boolean retryable, String message) {
        if (status < 200 || status >= 300) {
            boolean transientStatus = status == 429 || status >= 500;
            // Task 0025: the destination's answer gives the precise code; the internal code and retry are unchanged.
            com.securedoc.platform.domain.error.LvErrorCode lvCode = status == 401 || status == 403 ? com.securedoc.platform.domain.error.LvErrorCode.DLV_005
                    : status >= 400 && status < 500 && status != 429 ? com.securedoc.platform.domain.error.LvErrorCode.DLV_004
                    : transientStatus ? com.securedoc.platform.domain.error.LvErrorCode.DLV_002 : com.securedoc.platform.domain.error.LvErrorCode.DLV_007;
            throw failure(status >= 400 && status < 500 && status != 429
                            ? TechnicalFailureCode.DELIVERY_DESTINATION_INVALID : TechnicalFailureCode.DELIVERY_IO,
                    retryable || transientStatus, message, null).withLvCode(lvCode);
        }
    }
    private String text(JsonNode node, String field) { JsonNode v = node == null ? null : node.get(field); return v == null || v.isNull() ? null : v.asText(); }
    private String safeHeader(String v) { return (v == null ? "document.pdf" : v).replace("\r", "_").replace("\n", "_"); }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private TechnicalDependencyException failure(TechnicalFailureCode code, boolean retryable, String message, Throwable cause) {
        return new TechnicalDependencyException(TechnicalDependency.DELIVERY, code, retryable, message, cause);
    }
    private record SubmissionStatus(String status) { }
}
