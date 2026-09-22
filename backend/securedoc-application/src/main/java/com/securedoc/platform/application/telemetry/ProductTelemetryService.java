package com.securedoc.platform.application.telemetry;

import com.securedoc.platform.application.observability.StructuredLogContext;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Privacy-preserving product telemetry with a strict field allow-list.
 *
 * <p>No document content, OCR text, extracted values, business identifiers, file names, raw paths,
 * prompts, provider responses, actor identities or reversible tenant/package identifiers are accepted.
 * The dedicated PRODUCT_TELEMETRY logger is routed separately from normal application logs.
 */
public final class ProductTelemetryService {
    private static final Logger telemetry = LoggerFactory.getLogger("PRODUCT_TELEMETRY");

    private final ProductTelemetryMode mode;
    private final String installationTelemetryId;
    private final String applicationVersion;

    public ProductTelemetryService(String configuredMode,
                                   String configuredInstallationId,
                                   String applicationVersion) {
        ProductTelemetryMode requested = ProductTelemetryMode.parse(configuredMode);
        String installation = normalizeInstallationId(configuredInstallationId);
        this.mode = requested == ProductTelemetryMode.OFF || installation == null
                ? ProductTelemetryMode.OFF : requested;
        this.installationTelemetryId = installation;
        this.applicationVersion = applicationVersion == null || applicationVersion.isBlank()
                ? "unknown" : applicationVersion.trim();
    }

    public static ProductTelemetryService noop() {
        return new ProductTelemetryService("OFF", null, "unknown");
    }

    public ProductTelemetryMode mode() { return mode; }
    public boolean enabled() { return mode != ProductTelemetryMode.OFF; }
    public boolean installationIdConfigured() { return installationTelemetryId != null; }

    public void processingAttempt(TenantId tenantId, PackageId packageId,
                                  int attempt, int maxAttempts, boolean recovery, boolean legacyOrphan) {
        if (!enabled()) return;
        emit("package.processing.attempt", tenantId, packageId, fields(
                "stage", "DOCUMENT_PROCESSING",
                "attempt", attempt,
                "maxAttempts", maxAttempts,
                "recovery", recovery,
                "decision", legacyOrphan ? "LEGACY_ORPHAN_RECOVERY" : (recovery ? "LEASE_RECOVERY" : "NORMAL")));
    }

    public void processingFailure(TenantId tenantId, PackageId packageId,
                                  String stage, String component, String failureCategory,
                                  String reasonCode, boolean retryable, int attempt) {
        if (!enabled()) return;
        emit("package.processing.failure", tenantId, packageId, fields(
                "stage", safeToken(stage),
                "component", safeToken(component),
                "failureCategory", safeToken(failureCategory),
                "reasonCode", safeToken(reasonCode),
                "retryable", retryable,
                "attempt", Math.max(1, attempt)));
    }

    public void recoveryExhausted(TenantId tenantId, PackageId packageId, int attempts, int maxAttempts) {
        if (!enabled()) return;
        emit("package.processing.recovery_exhausted", tenantId, packageId, fields(
                "stage", "DOCUMENT_PROCESSING",
                "attempt", attempts,
                "maxAttempts", maxAttempts,
                "reasonCode", "PROCESSING_RECOVERY_EXHAUSTED",
                "status", "TECHNICAL_ATTENTION",
                "retryable", false));
    }

    public void processingFinished(TenantId tenantId, PackageId packageId,
                                   String status, long durationMs, int documentCount) {
        if (!enabled()) return;
        emit("package.processing.finished", tenantId, packageId, fields(
                "stage", "DOCUMENT_PROCESSING",
                "status", safeToken(status),
                "durationMs", Math.max(0L, durationMs),
                "documentCount", Math.max(0, documentCount)));
    }

    public void retentionPurged(TenantId tenantId, PackageId packageId,
                                String lifecycleType, String packageStatus) {
        if (!enabled()) return;
        emit("package.retention.purged", tenantId, packageId, fields(
                "feature", "PROCESSING_RETENTION",
                "decision", safeToken(lifecycleType),
                "status", safeToken(packageStatus)));
    }

    public void technicalAttentionExpired(TenantId tenantId, PackageId packageId) {
        if (!enabled()) return;
        emit("package.technical_attention.expired", tenantId, packageId, fields(
                "feature", "TECHNICAL_ATTENTION_RETENTION",
                "status", "AUTO_CLOSED",
                "decision", "PROCESSING_ARTIFACTS_PURGED"));
    }

    public void technicalAttentionAction(TenantId tenantId, PackageId packageId,
                                         String action, Integer holdDays) {
        if (!enabled()) return;
        Map<String, Object> values = fields(
                "feature", "TECHNICAL_ATTENTION",
                "decision", safeToken(action));
        if (holdDays != null) values.put("holdDays", Math.max(0, holdDays));
        emit("package.technical_attention.action", tenantId, packageId, values);
    }

    private void emit(String eventName, TenantId tenantId, PackageId packageId, Map<String, Object> eventFields) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("eventName", eventName);
        safe.put("telemetryMode", mode.name());
        safe.put("installationTelemetryId", installationTelemetryId);
        safe.put("tenantTelemetryId", pseudonym("tenant", tenantId.value()));
        if (mode == ProductTelemetryMode.ENHANCED) {
            safe.put("packageTelemetryId", pseudonym("package", packageId.value()));
        }
        safe.put("appVersion", applicationVersion);
        safe.putAll(eventFields);
        StructuredLogContext.info(telemetry, eventName, safe, "LexaVault privacy-safe product telemetry");
    }

    private String pseudonym(String namespace, UUID value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest((installationTelemetryId + ":" + namespace + ":" + value)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(24);
            for (int i = 0; i < 12; i++) out.append(String.format("%02x", hashed[i]));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String normalizeInstallationId(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim()).toString();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String safeToken(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_.-]+", "_");
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private static Map<String, Object> fields(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            if (entries[i] != null && entries[i + 1] != null) result.put(entries[i].toString(), entries[i + 1]);
        }
        return result;
    }
}
