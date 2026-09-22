package com.securedoc.platform.api.platform;

import com.securedoc.platform.api.config.AiRuntimeProperties;
import com.securedoc.platform.api.config.OllamaInferencePoolConfiguration;
import com.securedoc.platform.application.port.out.PackageLifecycleRepository;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.AuditQueryPort;
import com.securedoc.platform.application.port.out.ProcessingIncidentRepository;
import com.securedoc.platform.application.audit.query.AuditRecordFilter;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.processing.ProcessingIncident;
import com.securedoc.platform.domain.processing.ProcessingIncidentStatus;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.delivery.LocalDeliveryProperties;
import com.securedoc.platform.infrastructure.intake.WatchedFolderProperties;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrProperties;
import com.securedoc.platform.infrastructure.resilience.TechnicalResilienceProperties;
import com.securedoc.platform.application.storage.ProcessingStorageConfigurationService;
import com.securedoc.platform.domain.storage.ProcessingStorageProvider;
import com.securedoc.platform.domain.storage.ProcessingStorageProviderType;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Unit 24 safe technical visibility. Secrets/tokens are never serialized; only configuration state is returned.
 */
@Service
public class PlatformHealthService {
    private final DataSource dataSource;
    private final AiRuntimeProperties ai;
    private final TesseractOcrProperties ocr;
    private final ProcessingStorageConfigurationService processingStorage;
    private final LocalDeliveryProperties localDelivery;
    private final IntegrationConnectionRepository integrationConnections;
    private final WatchedFolderProperties watchedFolder;
    private final TechnicalResilienceProperties resilience;
    private final Environment environment;
    private final PackageRepository packageRepository;
    private final PackageLifecycleRepository lifecycleRepository;
    private final AuditQueryPort auditQueryPort;
    private final ProcessingIncidentRepository processingIncidentRepository;
    private final WatchedFolderScheduler watchedFolderScheduler;

    public PlatformHealthService(DataSource dataSource,
                                 AiRuntimeProperties ai,
                                 TesseractOcrProperties ocr,
                                 ProcessingStorageConfigurationService processingStorage,
                                 LocalDeliveryProperties localDelivery,
                                 IntegrationConnectionRepository integrationConnections,
                                 WatchedFolderProperties watchedFolder,
                                 TechnicalResilienceProperties resilience,
                                 Environment environment,
                                 PackageRepository packageRepository,
                                 PackageLifecycleRepository lifecycleRepository,
                                 AuditQueryPort auditQueryPort,
                                 ProcessingIncidentRepository processingIncidentRepository,
                                 WatchedFolderScheduler watchedFolderScheduler) {
        this.dataSource = dataSource;
        this.ai = ai;
        this.ocr = ocr;
        this.processingStorage = processingStorage;
        this.localDelivery = localDelivery;
        this.integrationConnections = integrationConnections;
        this.watchedFolder = watchedFolder;
        this.resilience = resilience;
        this.environment = environment;
        this.packageRepository = packageRepository;
        this.lifecycleRepository = lifecycleRepository;
        this.auditQueryPort = auditQueryPort;
        this.processingIncidentRepository = processingIncidentRepository;
        this.watchedFolderScheduler = watchedFolderScheduler;
    }

    public PlatformAdminOverviewResponse overview(TenantId tenantId, boolean probe) {
        List<PlatformHealthItem> providers = new ArrayList<>();
        providers.add(databaseHealth(probe));
        providers.add(aiHealth(probe));
        providers.add(ocrHealth(probe));
        providers.add(storageHealth(probe));
        providers.add(deliveryHealth(tenantId, probe));

        int packageProcessingMaxAttempts;
        try {
            packageProcessingMaxAttempts = Math.max(2, Integer.parseInt(environment.getProperty(
                    "securedoc.processing.coordination.max-processing-attempts", "3")));
        } catch (NumberFormatException ignored) {
            packageProcessingMaxAttempts = 3;
        }
        var resilienceSummary = new PlatformAdminOverviewResponse.ResilienceSummary(
                resilience.getMaxAttempts(), resilience.getInitialBackoffMs(), packageProcessingMaxAttempts,
                ai.isSecondaryEnabled(), ai.getSecondaryProvider(), safe(ai.getSecondaryModel()));
        String deliveryType = environment.getProperty("securedoc.delivery.type", "local");
        String identityMode = environment.getProperty("securedoc.security.mode", "OIDC").trim().toUpperCase();
        boolean sdvConfigured = integrationConnections.findEnabledSdvDeliveryByTenantId(tenantId).isPresent();
        var integrationSummary = new PlatformAdminOverviewResponse.IntegrationSummary(
                deliveryType, sdvConfigured, identityMode, "OIDC".equals(identityMode));

        Map<String, TechnicalExceptionItem> exceptionByPackage = new LinkedHashMap<>();

        // Structured incidents are authoritative for new failures and remain visible even when
        // business completes through manual continuity. Legacy audit discovery below preserves
        // visibility for incidents created before migration 021.
        Map<String, ProcessingIncident> latestIncidentByPackage = new LinkedHashMap<>();
        processingIncidentRepository.findRecentByTenantId(tenantId, 100).forEach(incident ->
                latestIncidentByPackage.putIfAbsent(incident.packageId().toString(), incident));
        latestIncidentByPackage.values().stream()
                .filter(incident -> incident.status() != ProcessingIncidentStatus.RESOLVED
                        && incident.status() != ProcessingIncidentStatus.AUTO_CLOSED)
                .forEach(incident -> packageRepository.findByIdAndTenantId(incident.packageId(), tenantId)
                        .ifPresent(pkg -> exceptionByPackage.putIfAbsent(pkg.id().toString(), technicalExceptionItem(pkg, tenantId, incident))));

        packageRepository.findByTenantId(tenantId, 0, 250).stream()
                .filter(pkg -> pkg.status() == PackageStatus.FAILED)
                .filter(pkg -> processingIncidentRepository.findLatestByPackageIdAndTenantId(pkg.id(), tenantId)
                        .map(incident -> incident.status() != ProcessingIncidentStatus.RESOLVED
                                && incident.status() != ProcessingIncidentStatus.AUTO_CLOSED)
                        .orElse(true))
                .forEach(pkg -> exceptionByPackage.putIfAbsent(pkg.id().toString(), technicalExceptionItem(pkg, tenantId)));

        auditQueryPort.findByFilter(new AuditRecordFilter(tenantId,
                        AuditAction.BUSINESS_MANUAL_FALLBACK_STARTED.name(), null, "DocumentPackage", null,
                        null, null, null, null, 100)).stream()
                .map(record -> record.resourceId())
                .filter(resourceId -> resourceId != null && !resourceId.isBlank())
                .distinct()
                .forEach(resourceId -> {
                    try {
                        packageRepository.findByIdAndTenantId(PackageId.of(UUID.fromString(resourceId)), tenantId)
                                .ifPresent(pkg -> exceptionByPackage.putIfAbsent(resourceId, technicalExceptionItem(pkg, tenantId)));
                    } catch (IllegalArgumentException ignored) {
                        // Audit data is immutable; ignore a malformed legacy resource identifier rather than breaking admin overview.
                    }
                });
        var exceptions = List.copyOf(exceptionByPackage.values());

        return new PlatformAdminOverviewResponse(Instant.now(), providers,
                watchedFolderScheduler.status(), resilienceSummary, integrationSummary, exceptions);
    }

    private TechnicalExceptionItem technicalExceptionItem(com.securedoc.platform.domain.document.DocumentPackage pkg,
                                                         TenantId tenantId,
                                                         ProcessingIncident incident) {
        Instant holdUntil = lifecycleRepository.findByPackageIdAndTenantId(pkg.id(), tenantId)
                .flatMap(lifecycle -> lifecycle.technicalHoldUntil()).orElse(null);
        return new TechnicalExceptionItem(pkg.id().toString(), incident.reasonCode(),
                pkg.status().name(), pkg.documents().size(), incident.firstObservedAt(), holdUntil);
    }

    private TechnicalExceptionItem technicalExceptionItem(com.securedoc.platform.domain.document.DocumentPackage pkg,
                                                         TenantId tenantId) {
        Instant holdUntil = lifecycleRepository.findByPackageIdAndTenantId(pkg.id(), tenantId)
                .flatMap(lifecycle -> lifecycle.technicalHoldUntil()).orElse(null);
        Instant failedAt = auditQueryPort.findByFilter(new AuditRecordFilter(tenantId,
                        AuditAction.DOCUMENT_PROCESSING_FAILED.name(), null, "DocumentPackage", pkg.id().toString(),
                        null, null, null, null, 20)).stream()
                .map(record -> record.occurredAt())
                .max(Instant::compareTo)
                .orElse(pkg.updatedAt());
        return new TechnicalExceptionItem(pkg.id().toString(), "TECHNICAL_FAILURE",
                pkg.status().name(), pkg.documents().size(), failedAt, holdUntil);
    }

    private PlatformHealthItem databaseHealth(boolean probe) {
        if (!probe) return item("database", "PostgreSQL", "Database", "postgresql", "CONFIGURED", true,
                "Datasource configured");
        try (var connection = dataSource.getConnection()) {
            boolean ok = connection.isValid(2);
            return item("database", "PostgreSQL", "Database", "postgresql", ok ? "HEALTHY" : "UNHEALTHY", true,
                    ok ? "Connection valid" : "Connection validation failed");
        } catch (Exception e) {
            return item("database", "PostgreSQL", "Database", "postgresql", "UNHEALTHY", true,
                    "Connection unavailable");
        }
    }

    private PlatformHealthItem aiHealth(boolean probe) {
        String provider = safe(ai.getProvider()).toLowerCase();
        boolean heuristic = "heuristic".equals(provider);
        boolean poolConfigured = ai.isPoolEnabled() && notBlank(ai.getPoolNodeUrls());
        boolean configured = notBlank(ai.getProvider())
                && (heuristic || (notBlank(ai.getModel()) && (poolConfigured || notBlank(ai.getBaseUrl()))));
        if (!probe || heuristic || !configured) {
            return item("ai", "Sensitive-content AI", "AI", provider, configured ? "CONFIGURED" : "NOT_CONFIGURED",
                    configured, aiDetail());
        }
        try {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            if ("ollama".equals(provider) && ai.isPoolEnabled()) {
                return ollamaPoolHealth(client, configured);
            }

            HttpRequest.Builder requestBuilder;
            if ("ollama".equals(provider)) {
                String base = ai.getBaseUrl().replaceAll("/+$", "");
                requestBuilder = HttpRequest.newBuilder(URI.create(base + "/api/tags"));
            } else if ("openai-compatible".equals(provider) || "openai_compatible".equals(provider) || "vllm".equals(provider)) {
                requestBuilder = HttpRequest.newBuilder(openAiModelsEndpoint(ai.getBaseUrl()));
                if (notBlank(ai.getApiKey())) {
                    requestBuilder.header("Authorization", "Bearer " + ai.getApiKey());
                }
            } else {
                return item("ai", "Sensitive-content AI", "AI", provider, "NOT_CONFIGURED", false, aiDetail());
            }
            var request = requestBuilder.timeout(Duration.ofSeconds(3)).GET().build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            boolean ok = status >= 200 && status < 300;
            return item("ai", "Sensitive-content AI", "AI", provider, ok ? "HEALTHY" : "UNHEALTHY", configured,
                    aiDetail());
        } catch (Exception e) {
            return item("ai", "Sensitive-content AI", "AI", provider, "UNHEALTHY", configured, aiDetail());
        }
    }

    private PlatformHealthItem ollamaPoolHealth(HttpClient client, boolean configured) {
        List<OllamaInferencePoolConfiguration.NodeTarget> nodes =
                OllamaInferencePoolConfiguration.parse(ai.getPoolNodeUrls());
        int ready = 0;
        int reachable = 0;
        for (OllamaInferencePoolConfiguration.NodeTarget node : nodes) {
            try {
                String base = node.baseUrl().replaceAll("/+$", "");
                var request = HttpRequest.newBuilder(URI.create(base + "/api/tags"))
                        .timeout(Duration.ofSeconds(3)).GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    reachable++;
                    if (ollamaResponseContainsModel(response.body(), ai.getModel())) {
                        ready++;
                    }
                }
            } catch (Exception ignored) {
                // Aggregate health deliberately tolerates individual-node failure.
            }
        }
        boolean ok = ready > 0;
        String detail = aiDetail() + "; ready nodes: " + ready + "/" + nodes.size()
                + "; reachable nodes: " + reachable + "/" + nodes.size();
        return item("ai", "Sensitive-content AI", "AI", "ollama",
                ok ? "HEALTHY" : "UNHEALTHY", configured, detail);
    }

    private boolean ollamaResponseContainsModel(String responseBody, String requiredModel) {
        if (responseBody == null || responseBody.isBlank() || requiredModel == null || requiredModel.isBlank()) {
            return false;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(responseBody);
            for (com.fasterxml.jackson.databind.JsonNode candidate : root.path("models")) {
                if (requiredModel.equals(candidate.path("name").asText(""))
                        || requiredModel.equals(candidate.path("model").asText(""))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private String aiDetail() {
        String model = safe(ai.getModel());
        int tenantProfiles = ai.getTenantProfiles().size();
        String base = model.isBlank() ? "Provider selected" : "Default model: " + model;
        if (ai.isPoolEnabled()) {
            int configuredNodes;
            try {
                configuredNodes = OllamaInferencePoolConfiguration.parse(ai.getPoolNodeUrls()).size();
            } catch (RuntimeException ignored) {
                configuredNodes = 0;
            }
            base += "; inference pool: enabled; configured nodes: " + configuredNodes;
        } else {
            base += "; inference pool: disabled";
        }
        return base + "; tenant profiles: " + tenantProfiles;
    }

    private URI openAiModelsEndpoint(String baseUrl) {
        String normalized = safe(baseUrl).replaceAll("/+$", "");
        if (normalized.endsWith("/v1")) {
            return URI.create(normalized + "/models");
        }
        if (normalized.endsWith("/chat/completions")) {
            int index = normalized.lastIndexOf("/chat/completions");
            return URI.create(normalized.substring(0, index) + "/models");
        }
        return URI.create(normalized + "/v1/models");
    }

    private PlatformHealthItem ocrHealth(boolean probe) {
        boolean configured = notBlank(ocr.getExecutable());
        if (!probe) return item("ocr", "OCR", "OCR", "tesseract", configured ? "CONFIGURED" : "NOT_CONFIGURED",
                configured, "Language: " + safe(ocr.getLanguage()));
        try {
            Process process = new ProcessBuilder(ocr.getExecutable(), "--version").redirectErrorStream(true).start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            boolean ok = finished && process.exitValue() == 0;
            if (!finished) process.destroyForcibly();
            return item("ocr", "OCR", "OCR", "tesseract", ok ? "HEALTHY" : "UNHEALTHY", configured,
                    "Language: " + safe(ocr.getLanguage()));
        } catch (Exception e) {
            return item("ocr", "OCR", "OCR", "tesseract", "UNHEALTHY", configured,
                    "Tesseract executable unavailable");
        }
    }

    private PlatformHealthItem storageHealth(boolean probe) {
        try {
            var overview = processingStorage.overview();
            if (overview.deploymentDefault() == null) {
                return item("storage", "Processing storage", "Storage", "managed", "NOT_CONFIGURED", false,
                        "Platform Operations setup required; document intake/processing is disabled");
            }
            ProcessingStorageProvider provider = overview.providers().stream()
                    .filter(p -> p.id().equals(overview.deploymentDefault().providerId()))
                    .findFirst().orElse(null);
            String providerType = provider == null ? "managed" : provider.providerType().name().toLowerCase();
            String status = overview.ready() ? "HEALTHY" : "UNHEALTHY";
            boolean unsafeLocalCluster = provider != null
                    && provider.providerType() == ProcessingStorageProviderType.LOCAL_FILESYSTEM
                    && overview.activeNodeCount() > 1;
            String detail = overview.ready()
                    ? "Configured and validated on every active node"
                    : unsafeLocalCluster
                        ? "LOCAL_FILESYSTEM is not permitted with multiple active application nodes; activate SHARED_FILESYSTEM"
                        : "Configured but not healthy on every active node";
            return item("storage", "Processing storage", "Storage", providerType, status, true, detail);
        } catch (Exception e) {
            return item("storage", "Processing storage", "Storage", "managed", "UNHEALTHY", false,
                    "Processing storage configuration unavailable");
        }
    }

    private PlatformHealthItem deliveryHealth(TenantId tenantId, boolean probe) {
        String type = environment.getProperty("securedoc.delivery.type", "sdv").trim().toLowerCase();
        if ("sdv".equals(type)) {
            var configured = integrationConnections.findEnabledSdvDeliveryByTenantId(tenantId);
            if (configured.isEmpty()) {
                return item("delivery", "Final delivery", "Delivery", "sdv", "NOT_CONFIGURED", false,
                        "No enabled tenant SDV delivery connection");
            }
            var c = configured.get();
            String status = c.healthStatus().name();
            return item("delivery", "Final delivery", "Delivery", "sdv", status, true,
                    "Tenant SDV connection configured; secrets are protected");
        }
        Path output = Path.of(localDelivery.getOutputPath()).toAbsolutePath().normalize();
        if (!probe) return item("delivery", "Final delivery", "Delivery", "local", "CONFIGURED", true,
                "Local filesystem destination");
        try {
            Files.createDirectories(output);
            boolean ok = Files.isDirectory(output) && Files.isWritable(output);
            return item("delivery", "Final delivery", "Delivery", "local", ok ? "HEALTHY" : "UNHEALTHY", true,
                    "Local filesystem destination");
        } catch (Exception e) {
            return item("delivery", "Final delivery", "Delivery", "local", "UNHEALTHY", true,
                    "Local destination unavailable");
        }
    }

    private static PlatformHealthItem item(String key, String label, String category, String provider,
                                           String status, boolean configured, String detail) {
        return new PlatformHealthItem(key, label, category, safe(provider), status, configured, detail);
    }

    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private static String safe(String value) { return value == null ? "" : value; }
}
