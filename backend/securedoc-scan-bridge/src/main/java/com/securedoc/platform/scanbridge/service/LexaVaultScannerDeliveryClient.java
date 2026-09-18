package com.securedoc.platform.scanbridge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.model.ScanSession;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Streaming/idempotent HTTP client for the existing LexaVault machine-intake boundary. */
@Component
public class LexaVaultScannerDeliveryClient {
    public static final String ACCEPTED_FOR_PROCESSING = "ACCEPTED_FOR_PROCESSING";

    private final ScanBridgeProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LexaVaultScannerDeliveryClient(ScanBridgeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public IntakeStatus createIntake(ScanSession session, boolean retry) {
        requirePrepared(session);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("intakeChannel", "SCANNER");
        metadata.put("scannerCaptureMode", session.captureMode().name());
        metadata.put("bridgeId", session.bridgeId());
        metadata.put("siteId", session.siteId());
        metadata.put("scannerId", session.scannerId());
        metadata.put("scanSessionId", session.sessionId().toString());
        metadata.put("scanStartedAt", session.createdAt().toString());
        metadata.put("scanCompletedAt", session.preparedScan().receivedAt().toString());
        if (session.queuedAt() == null) {
            throw new IllegalStateException("Scanner intake must be durably queued before LexaVault submission");
        }
        metadata.put("scannerSubmittedAt", session.queuedAt().toString());
        metadata.put("sourceSubmissionId", session.submissionId().toString());
        metadata.put("sourceDocumentId", session.preparedScan().sourceDocumentId().toString());
        metadata.put("sourceFileSizeBytes", Long.toString(session.preparedScan().sizeBytes()));
        metadata.put("sourceSha256", session.preparedScan().sha256());
        if (session.preparedScan().pageCount() != null) {
            metadata.put("scannerReportedPageCount", session.preparedScan().pageCount().toString());
        }

        CreateRequest body = new CreateRequest(
                session.submissionId().toString(),
                "1",
                session.businessIdentifiers(),
                Map.copyOf(metadata),
                List.of(new ManifestDocument(session.preparedScan().sourceDocumentId().toString(),
                        session.preparedScan().fileName(), session.preparedScan().sha256())),
                retry);
        try {
            byte[] json = objectMapper.writeValueAsBytes(body);
            HttpRequest.Builder builder = requestBuilder("/scanner-intakes")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json));
            return parseIntake(send(builder.build()));
        } catch (IOException ex) {
            throw DeliveryException.retryable("LEXAVAULT_REQUEST_SERIALIZATION_FAILED", ex);
        }
    }

    public IntakeStatus status(UUID packageId) {
        HttpRequest request = requestBuilder("/scanner-intakes/" + packageId).GET().build();
        return parseIntake(send(request));
    }

    public DocumentReceipt upload(ScanSession session, Path pdf) {
        requirePrepared(session);
        if (session.lexavaultPackageId() == null) throw new IllegalArgumentException("LexaVault package id is required before upload");
        String path = "/scanner-intakes/" + session.lexavaultPackageId() + "/documents/"
                + session.preparedScan().sourceDocumentId();
        HttpRequest request = requestBuilder(path)
                .header("Content-Type", "application/pdf")
                .header("Idempotency-Key", session.submissionId() + ":" + session.preparedScan().sourceDocumentId())
                .header("X-Content-SHA256", session.preparedScan().sha256())
                .POST(filePublisher(pdf))
                .build();
        HttpResponse<byte[]> response = send(request);
        try {
            JsonNode json = objectMapper.readTree(response.body());
            return new DocumentReceipt(
                    UUID.fromString(requiredText(json, "packageId")),
                    requiredText(json, "intakeStatus"),
                    json.path("duplicateAcknowledgement").asBoolean(false));
        } catch (Exception ex) {
            throw DeliveryException.retryable("LEXAVAULT_INVALID_RESPONSE", ex);
        }
    }

    private HttpRequest.BodyPublisher filePublisher(Path pdf) {
        try {
            return HttpRequest.BodyPublishers.ofFile(pdf);
        } catch (java.io.FileNotFoundException ex) {
            throw DeliveryException.terminal("LOCAL_SCAN_ARTIFACT_MISSING", ex);
        }
    }

    private IntakeStatus parseIntake(HttpResponse<byte[]> response) {
        try {
            JsonNode json = objectMapper.readTree(response.body());
            return new IntakeStatus(UUID.fromString(requiredText(json, "packageId")),
                    requiredText(json, "intakeStatus"), requiredText(json, "packageStatus"));
        } catch (Exception ex) {
            throw DeliveryException.retryable("LEXAVAULT_INVALID_RESPONSE", ex);
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 200 && response.statusCode() < 300) return response;
            throw toDeliveryException(response);
        } catch (DeliveryException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw DeliveryException.retryable("LEXAVAULT_REQUEST_INTERRUPTED", ex);
        } catch (IOException ex) {
            throw DeliveryException.retryable("LEXAVAULT_UNAVAILABLE", ex);
        }
    }

    private DeliveryException toDeliveryException(HttpResponse<byte[]> response) {
        String code = "LEXAVAULT_HTTP_" + response.statusCode();
        try {
            JsonNode json = objectMapper.readTree(response.body());
            if (json.hasNonNull("errorCode") && !json.get("errorCode").asText().isBlank()) {
                code = json.get("errorCode").asText().trim();
            }
        } catch (Exception ignored) {
            // Never include response body in logs/errors; keep only safe status-derived code.
        }
        int status = response.statusCode();
        boolean retryable = status == 408 || status == 425 || status == 429 || status >= 500;
        return retryable ? DeliveryException.retryable(code, null) : DeliveryException.terminal(code, null);
    }

    private HttpRequest.Builder requestBuilder(String path) {
        String base = properties.getLexavaultBaseUrl();
        if (base == null || base.isBlank()) throw DeliveryException.terminal("LEXAVAULT_ENDPOINT_NOT_CONFIGURED", null);
        base = base.replaceAll("/+$", "");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(Math.max(5, properties.getDelivery().getRequestTimeoutSeconds())))
                .header("Accept", "application/json")
                .header("X-Correlation-Id", UUID.randomUUID().toString());
        String token = properties.getAuthorizationBearerToken();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token.trim());
        } else {
            builder.header("X-Tenant-Id", properties.getTenantId())
                    .header("X-Actor-Id", properties.getLocalActorId())
                    .header("X-Integration-Client", properties.getClientPrincipal());
        }
        return builder;
    }

    private String requiredText(JsonNode json, String field) {
        String value = json.path(field).asText(null);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing response field " + field);
        return value;
    }

    private void requirePrepared(ScanSession session) {
        if (session == null || session.preparedScan() == null) throw new IllegalArgumentException("Prepared scanner session is required");
    }

    private record CreateRequest(String externalSourcePackageId, String sourceRevision,
                                 Map<String, String> businessIdentifiers, Map<String, String> metadata,
                                 List<ManifestDocument> documents, boolean retry) { }
    private record ManifestDocument(String externalSourceDocumentId, String fileName, String sha256) { }
    public record IntakeStatus(UUID packageId, String intakeStatus, String packageStatus) { }
    public record DocumentReceipt(UUID packageId, String intakeStatus, boolean duplicateAcknowledgement) { }

    public static final class DeliveryException extends RuntimeException {
        private final String safeCode;
        private final boolean retryable;

        private DeliveryException(String safeCode, boolean retryable, Throwable cause) {
            super(safeCode, cause);
            this.safeCode = safeCode;
            this.retryable = retryable;
        }
        public String safeCode() { return safeCode; }
        public boolean retryable() { return retryable; }
        public static DeliveryException retryable(String code, Throwable cause) { return new DeliveryException(code, true, cause); }
        public static DeliveryException terminal(String code, Throwable cause) { return new DeliveryException(code, false, cause); }
    }
}
