package com.securedoc.platform.api.platform;

import com.securedoc.platform.api.config.AiRuntimeProperties;
import com.securedoc.platform.application.port.out.PackageLifecycleRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.delivery.ApiDeliveryProperties;
import com.securedoc.platform.infrastructure.delivery.LocalDeliveryProperties;
import com.securedoc.platform.infrastructure.delivery.S3DeliveryProperties;
import com.securedoc.platform.infrastructure.intake.WatchedFolderProperties;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrProperties;
import com.securedoc.platform.infrastructure.resilience.TechnicalResilienceProperties;
import com.securedoc.platform.infrastructure.storage.LocalStorageProperties;
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

/**
 * Unit 24 safe technical visibility. Secrets/tokens are never serialized; only configuration state is returned.
 */
@Service
public class PlatformHealthService {
    private final DataSource dataSource;
    private final AiRuntimeProperties ai;
    private final TesseractOcrProperties ocr;
    private final LocalStorageProperties storage;
    private final LocalDeliveryProperties localDelivery;
    private final ApiDeliveryProperties apiDelivery;
    private final S3DeliveryProperties s3Delivery;
    private final WatchedFolderProperties watchedFolder;
    private final TechnicalResilienceProperties resilience;
    private final Environment environment;
    private final PackageRepository packageRepository;
    private final PackageLifecycleRepository lifecycleRepository;
    private final WatchedFolderScheduler watchedFolderScheduler;

    public PlatformHealthService(DataSource dataSource,
                                 AiRuntimeProperties ai,
                                 TesseractOcrProperties ocr,
                                 LocalStorageProperties storage,
                                 LocalDeliveryProperties localDelivery,
                                 ApiDeliveryProperties apiDelivery,
                                 S3DeliveryProperties s3Delivery,
                                 WatchedFolderProperties watchedFolder,
                                 TechnicalResilienceProperties resilience,
                                 Environment environment,
                                 PackageRepository packageRepository,
                                 PackageLifecycleRepository lifecycleRepository,
                                 WatchedFolderScheduler watchedFolderScheduler) {
        this.dataSource = dataSource;
        this.ai = ai;
        this.ocr = ocr;
        this.storage = storage;
        this.localDelivery = localDelivery;
        this.apiDelivery = apiDelivery;
        this.s3Delivery = s3Delivery;
        this.watchedFolder = watchedFolder;
        this.resilience = resilience;
        this.environment = environment;
        this.packageRepository = packageRepository;
        this.lifecycleRepository = lifecycleRepository;
        this.watchedFolderScheduler = watchedFolderScheduler;
    }

    public PlatformAdminOverviewResponse overview(TenantId tenantId, boolean probe) {
        List<PlatformHealthItem> providers = new ArrayList<>();
        providers.add(databaseHealth(probe));
        providers.add(aiHealth(probe));
        providers.add(ocrHealth(probe));
        providers.add(storageHealth(probe));
        providers.add(deliveryHealth(probe));

        var resilienceSummary = new PlatformAdminOverviewResponse.ResilienceSummary(
                resilience.getMaxAttempts(), resilience.getInitialBackoffMs(),
                ai.isSecondaryEnabled(), ai.getSecondaryProvider(), safe(ai.getSecondaryModel()));
        String deliveryType = environment.getProperty("securedoc.delivery.type", "local");
        String identityMode = environment.getProperty("securedoc.security.mode", "OIDC").trim().toUpperCase();
        var integrationSummary = new PlatformAdminOverviewResponse.IntegrationSummary(
                deliveryType,
                notBlank(apiDelivery.getEndpoint()),
                notBlank(s3Delivery.getBucket()),
                identityMode,
                "OIDC".equals(identityMode));

        var exceptions = packageRepository.findByTenantId(tenantId, 0, 250).stream()
                .filter(pkg -> pkg.status() == PackageStatus.FAILED)
                .map(pkg -> {
                    Instant holdUntil = lifecycleRepository.findByPackageIdAndTenantId(pkg.id(), tenantId)
                            .flatMap(lifecycle -> lifecycle.technicalHoldUntil()).orElse(null);
                    return new TechnicalExceptionItem(pkg.id().toString(), "TECHNICAL_FAILURE",
                            pkg.status().name(), pkg.documents().size(), pkg.updatedAt(), holdUntil);
                })
                .toList();

        return new PlatformAdminOverviewResponse(Instant.now(), providers,
                watchedFolderScheduler.status(), resilienceSummary, integrationSummary, exceptions);
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
        boolean configured = notBlank(ai.getProvider())
                && (heuristic || (notBlank(ai.getBaseUrl()) && notBlank(ai.getModel())));
        if (!probe || heuristic || !configured) {
            return item("ai", "Sensitive-content AI", "AI", provider, configured ? "CONFIGURED" : "NOT_CONFIGURED",
                    configured, aiDetail());
        }
        try {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
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

    private String aiDetail() {
        String model = safe(ai.getModel());
        int tenantProfiles = ai.getTenantProfiles().size();
        String base = model.isBlank() ? "Provider selected" : "Default model: " + model;
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
        Path root = Path.of(storage.getRootPath()).toAbsolutePath().normalize();
        if (!probe) return item("storage", "Internal storage", "Storage", "local", "CONFIGURED", true,
                "Local filesystem");
        try {
            Files.createDirectories(root);
            boolean ok = Files.isDirectory(root) && Files.isWritable(root);
            return item("storage", "Internal storage", "Storage", "local", ok ? "HEALTHY" : "UNHEALTHY", true,
                    "Local filesystem");
        } catch (Exception e) {
            return item("storage", "Internal storage", "Storage", "local", "UNHEALTHY", true,
                    "Filesystem unavailable");
        }
    }

    private PlatformHealthItem deliveryHealth(boolean probe) {
        String type = environment.getProperty("securedoc.delivery.type", "local").trim().toLowerCase();
        return switch (type) {
            case "api" -> {
                boolean configured = notBlank(apiDelivery.getEndpoint()) && (!apiDelivery.isRequireAuthentication() || notBlank(apiDelivery.getBearerToken()));
                yield item("delivery", "Final delivery", "Delivery", "api", configured ? "CONFIGURED" : "NOT_CONFIGURED",
                        configured, "API callback " + (configured ? "configured" : "missing required connection settings"));
            }
            case "s3" -> {
                boolean configured = notBlank(s3Delivery.getBucket());
                yield item("delivery", "Final delivery", "Delivery", "s3", configured ? "CONFIGURED" : "NOT_CONFIGURED",
                        configured, "S3-compatible destination " + (configured ? "configured" : "missing bucket"));
            }
            default -> {
                Path output = Path.of(localDelivery.getOutputPath()).toAbsolutePath().normalize();
                if (!probe) yield item("delivery", "Final delivery", "Delivery", "local", "CONFIGURED", true,
                        "Local filesystem destination");
                try {
                    Files.createDirectories(output);
                    boolean ok = Files.isDirectory(output) && Files.isWritable(output);
                    yield item("delivery", "Final delivery", "Delivery", "local", ok ? "HEALTHY" : "UNHEALTHY", true,
                            "Local filesystem destination");
                } catch (Exception e) {
                    yield item("delivery", "Final delivery", "Delivery", "local", "UNHEALTHY", true,
                            "Local destination unavailable");
                }
            }
        };
    }

    private static PlatformHealthItem item(String key, String label, String category, String provider,
                                           String status, boolean configured, String detail) {
        return new PlatformHealthItem(key, label, category, safe(provider), status, configured, detail);
    }

    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private static String safe(String value) { return value == null ? "" : value; }
}
