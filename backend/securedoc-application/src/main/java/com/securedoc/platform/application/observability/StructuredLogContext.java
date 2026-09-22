package com.securedoc.platform.application.observability;

import org.slf4j.Logger;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Privacy-safe structured logging context shared by request, async, and processing code.
 *
 * <p>Only explicitly allow-listed operational keys may enter MDC. This prevents accidental
 * propagation of document text, OCR text, prompts, provider responses, or protected values.
 */
public final class StructuredLogContext {

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "tenantId", "actorId", "correlationId", "packageId", "documentId", "incidentId",
            "eventName", "stage", "component", "failureCategory", "reasonCode", "provider", "model",
            "attempt", "maxAttempts", "retryable", "durationMs", "status", "decision", "scope",
            "inferenceNodeId", "inflightCount", "healthyNodeCount", "nextNodeEligible", "configuredNodeCount",
            "businessOutcome", "pageCount", "batchCount", "providerCalls", "pageRetries", "findingCount",
            "parallelism", "pageBatchSize", "pageBatchMaxCharacters",
            "providerLoadMs", "promptEvalTokens", "providerPromptEvalMs", "outputEvalTokens", "providerEvalMs",
            "processingCycle", "intelligenceRequestedMode", "intelligenceEffectiveMode", "intelligenceBundleVersion",
            "intelligencePolicyVersion", "intelligenceExecutionAllowed", "intelligenceInfluenceAllowed",
            "baselineDiscoveryRequired", "intelligenceFallbackToBaseline",
            "queueWaitMs", "activeWorkers", "workerPoolSize", "workerCorePoolSize", "recovery",
            "workerMaxPoolSize", "workerQueueDepth", "workerQueueRemaining",
            "exportCycle", "enabledRules", "documents", "matchedRules", "ambiguousPages",
            // Dedicated privacy-safe product telemetry identifiers/metadata. These are pseudonymous
            // or deployment-controlled tokens and never contain customer document/business content.
            "telemetryMode", "installationTelemetryId", "tenantTelemetryId", "packageTelemetryId",
            "appVersion", "feature", "documentCount", "holdDays"
    );

    private StructuredLogContext() {
    }

    public static Scope open(Map<String, ?> fields) {
        Map<String, String> previous = new LinkedHashMap<>();
        if (fields != null) {
            fields.forEach((key, value) -> {
                if (!ALLOWED_KEYS.contains(key) || value == null) return;
                previous.put(key, MDC.get(key));
                MDC.put(key, safeValue(value));
            });
        }
        return new Scope(previous);
    }

    public static void put(String key, Object value) {
        if (!ALLOWED_KEYS.contains(key) || value == null) return;
        MDC.put(key, safeValue(value));
    }

    public static void remove(String key) {
        if (ALLOWED_KEYS.contains(key)) MDC.remove(key);
    }

    public static void clearRequestContext() {
        MDC.remove("tenantId");
        MDC.remove("actorId");
        MDC.remove("correlationId");
        MDC.remove("packageId");
        MDC.remove("documentId");
        MDC.remove("incidentId");
        MDC.remove("eventName");
        MDC.remove("stage");
        MDC.remove("component");
        MDC.remove("failureCategory");
        MDC.remove("reasonCode");
        MDC.remove("provider");
        MDC.remove("model");
        MDC.remove("attempt");
        MDC.remove("maxAttempts");
        MDC.remove("retryable");
        MDC.remove("durationMs");
        MDC.remove("status");
        MDC.remove("decision");
        MDC.remove("scope");
        MDC.remove("inferenceNodeId");
        MDC.remove("inflightCount");
        MDC.remove("healthyNodeCount");
        MDC.remove("nextNodeEligible");
        MDC.remove("configuredNodeCount");
        MDC.remove("businessOutcome");
        MDC.remove("pageCount");
        MDC.remove("batchCount");
        MDC.remove("providerCalls");
        MDC.remove("pageRetries");
        MDC.remove("findingCount");
        MDC.remove("parallelism");
        MDC.remove("pageBatchSize");
        MDC.remove("pageBatchMaxCharacters");
        MDC.remove("providerLoadMs");
        MDC.remove("promptEvalTokens");
        MDC.remove("providerPromptEvalMs");
        MDC.remove("outputEvalTokens");
        MDC.remove("providerEvalMs");
        MDC.remove("processingCycle");
        MDC.remove("intelligenceRequestedMode");
        MDC.remove("intelligenceEffectiveMode");
        MDC.remove("intelligenceBundleVersion");
        MDC.remove("intelligencePolicyVersion");
        MDC.remove("intelligenceExecutionAllowed");
        MDC.remove("intelligenceInfluenceAllowed");
        MDC.remove("baselineDiscoveryRequired");
        MDC.remove("intelligenceFallbackToBaseline");
        MDC.remove("queueWaitMs");
        MDC.remove("recovery");
        MDC.remove("activeWorkers");
        MDC.remove("workerPoolSize");
        MDC.remove("workerCorePoolSize");
        MDC.remove("workerMaxPoolSize");
        MDC.remove("workerQueueDepth");
        MDC.remove("workerQueueRemaining");
        MDC.remove("exportCycle");
        MDC.remove("enabledRules");
        MDC.remove("documents");
        MDC.remove("matchedRules");
        MDC.remove("ambiguousPages");
        MDC.remove("telemetryMode");
        MDC.remove("installationTelemetryId");
        MDC.remove("tenantTelemetryId");
        MDC.remove("packageTelemetryId");
        MDC.remove("appVersion");
        MDC.remove("feature");
        MDC.remove("documentCount");
        MDC.remove("holdDays");
    }

    public static Map<String, String> snapshot() {
        Map<String, String> current = MDC.getCopyOfContextMap();
        if (current == null || current.isEmpty()) return Map.of();
        Map<String, String> safe = new LinkedHashMap<>();
        current.forEach((key, value) -> {
            if (ALLOWED_KEYS.contains(key) && value != null) safe.put(key, value);
        });
        return Map.copyOf(safe);
    }

    public static Runnable wrap(Runnable delegate) {
        Map<String, String> captured = snapshot();
        return () -> runWithSnapshot(captured, delegate);
    }

    public static <T> Callable<T> wrap(Callable<T> delegate) {
        Map<String, String> captured = snapshot();
        return () -> callWithSnapshot(captured, delegate);
    }

    public static void info(Logger logger, String eventName, Map<String, ?> fields, String message) {
        try (Scope ignored = open(withEvent(eventName, fields))) {
            logger.info(message);
        }
    }

    public static void warn(Logger logger, String eventName, Map<String, ?> fields, String message) {
        try (Scope ignored = open(withEvent(eventName, fields))) {
            logger.warn(message);
        }
    }

    public static void error(Logger logger, String eventName, Map<String, ?> fields, String message) {
        try (Scope ignored = open(withEvent(eventName, fields))) {
            logger.error(message);
        }
    }

    private static Map<String, ?> withEvent(String eventName, Map<String, ?> fields) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (fields != null) merged.putAll(fields);
        merged.put("eventName", eventName);
        return merged;
    }

    private static void runWithSnapshot(Map<String, String> captured, Runnable delegate) {
        Map<String, String> before = MDC.getCopyOfContextMap();
        try {
            MDC.clear();
            captured.forEach(MDC::put);
            delegate.run();
        } finally {
            restore(before);
        }
    }

    private static <T> T callWithSnapshot(Map<String, String> captured, Callable<T> delegate) throws Exception {
        Map<String, String> before = MDC.getCopyOfContextMap();
        try {
            MDC.clear();
            captured.forEach(MDC::put);
            return delegate.call();
        } finally {
            restore(before);
        }
    }

    private static void restore(Map<String, String> before) {
        MDC.clear();
        if (before != null) before.forEach(MDC::put);
    }

    private static String safeValue(Object value) {
        String text = String.valueOf(value).trim();
        return text.length() <= 200 ? text : text.substring(0, 200);
    }

    public static final class Scope implements AutoCloseable {
        private final Map<String, String> previous;
        private boolean closed;

        private Scope(Map<String, String> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            previous.forEach((key, value) -> {
                if (value == null) MDC.remove(key); else MDC.put(key, value);
            });
            closed = true;
        }
    }
}
