package com.securedoc.platform.scanbridge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.model.BridgeLvCodes;
import com.securedoc.platform.scanbridge.model.ScanSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
    private static final java.util.regex.Pattern SAFE_ERROR_CODE = java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{0,79}");

    private final ScanBridgeProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Clock clock;

    @Autowired
    public LexaVaultScannerDeliveryClient(ScanBridgeProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.systemUTC());
    }

    LexaVaultScannerDeliveryClient(ScanBridgeProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
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
                retry, session.caseSensitivityLevel());
        try {
            byte[] json = objectMapper.writeValueAsBytes(body);
            HttpRequest.Builder builder = requestBuilder("/scanner-intakes")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json));
            return parseIntake(send(builder.build()));
        } catch (IOException ex) {
            // Task 0023 D2 (#82): serialising our own request fails the same way every time; do not retry it.
            throw DeliveryException.terminal("LEXAVAULT_REQUEST_SERIALIZATION_FAILED", ex);
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
        int status = response.statusCode();
        String code = BridgeLvCodes.LEXAVAULT_HTTP_PREFIX + status;
        String lexaVaultLvCode = null;
        try {
            JsonNode json = objectMapper.readTree(response.body());
            if (json.hasNonNull("errorCode")) {
                String candidate = json.get("errorCode").asText().trim();
                // Only a code-shaped value reaches the screen and the log; free text could hold content.
                if (SAFE_ERROR_CODE.matcher(candidate).matches()) code = candidate;
            }
            // Task 0025: LexaVault's own LV code, kept as the detail when it is LV-shaped. Its message is never read.
            if (json.hasNonNull("code")) lexaVaultLvCode = BridgeLvCodes.safeLexaVaultLvCode(json.get("code").asText());
        } catch (Exception ignored) {
            // Never include response body in logs/errors; keep only safe status-derived code.
        }
        if (!retryableStatus(status)) return DeliveryException.terminal(code, null, status, lexaVaultLvCode);
        Duration retryAfter = status == 429
                ? retryAfter(response.headers().firstValue("Retry-After").orElse(null), clock.instant())
                : null;
        return DeliveryException.retryable(code, null, retryAfter, status, lexaVaultLvCode);
    }

    /**
     * Task 0023 D2 (#82): a 4xx is LexaVault refusing this request and the same request gets the same answer,
     * so a 4xx goes straight to a person. Task 0024: except 429 (LexaVault asks us to slow down), which is
     * retried like a 5xx. {@link ScannerSubmissionWorker} caps those retries.
     */
    static boolean retryableStatus(int status) {
        return status >= 500 || status == 429;
    }

    /**
     * Task 0024: a {@code Retry-After} value as a wait from {@code now}: delta-seconds or an HTTP-date. A date
     * in the past is zero. Missing or unreadable is null (the caller then uses the normal spacing). The upper
     * bound is applied by the worker.
     */
    static Duration retryAfter(String header, Instant now) {
        if (header == null || header.isBlank()) return null;
        String value = header.trim();
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                return Duration.ofSeconds(Long.parseLong(value));
            } catch (NumberFormatException tooLarge) {
                return Duration.ofSeconds(Long.MAX_VALUE);
            }
        }
        try {
            Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return at.isAfter(now) ? Duration.between(now, at) : Duration.ZERO;
        } catch (DateTimeParseException unreadable) {
            return null;
        }
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
                                 List<ManifestDocument> documents, boolean retry, String caseSensitivityLevel) { }
    private record ManifestDocument(String externalSourceDocumentId, String fileName, String sha256) { }
    public record IntakeStatus(UUID packageId, String intakeStatus, String packageStatus) { }
    public record DocumentReceipt(UUID packageId, String intakeStatus, boolean duplicateAcknowledgement) { }

    public static final class DeliveryException extends RuntimeException {
        private final String safeCode;
        private final boolean retryable;
        private final Duration retryAfter;
        private final Integer httpStatus;
        private final String lexaVaultLvCode;

        private DeliveryException(String safeCode, boolean retryable, Throwable cause, Duration retryAfter,
                                  Integer httpStatus, String lexaVaultLvCode) {
            super(safeCode, cause);
            this.safeCode = safeCode;
            this.retryable = retryable;
            this.retryAfter = retryAfter;
            this.httpStatus = httpStatus;
            this.lexaVaultLvCode = BridgeLvCodes.safeLexaVaultLvCode(lexaVaultLvCode);
        }
        public String safeCode() { return safeCode; }
        public boolean retryable() { return retryable; }
        /** Task 0024: the wait LexaVault asked for (429 Retry-After), unbounded; null when it gave none. */
        public Duration retryAfter() { return retryAfter; }
        /** Task 0025: the HTTP status of LexaVault's answer; null when LexaVault gave no answer (or a 2xx). */
        public Integer httpStatus() { return httpStatus; }
        /** Task 0025: LexaVault's own LV code from its error body (LV-shaped only); null when none. */
        public String lexaVaultLvCode() { return lexaVaultLvCode; }
        public static DeliveryException retryable(String code, Throwable cause) { return new DeliveryException(code, true, cause, null, null, null); }
        public static DeliveryException retryable(String code, Throwable cause, Duration retryAfter) {
            return new DeliveryException(code, true, cause, retryAfter, null, null);
        }
        public static DeliveryException retryable(String code, Throwable cause, Duration retryAfter,
                                                  Integer httpStatus, String lexaVaultLvCode) {
            return new DeliveryException(code, true, cause, retryAfter, httpStatus, lexaVaultLvCode);
        }
        public static DeliveryException terminal(String code, Throwable cause) { return new DeliveryException(code, false, cause, null, null, null); }
        public static DeliveryException terminal(String code, Throwable cause, Integer httpStatus, String lexaVaultLvCode) {
            return new DeliveryException(code, false, cause, null, httpStatus, lexaVaultLvCode);
        }
    }
}
