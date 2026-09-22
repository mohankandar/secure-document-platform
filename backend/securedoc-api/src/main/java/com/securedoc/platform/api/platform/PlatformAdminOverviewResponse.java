package com.securedoc.platform.api.platform;

import java.time.Instant;
import java.util.List;

public record PlatformAdminOverviewResponse(
        Instant generatedAt,
        List<PlatformHealthItem> providers,
        WatchedFolderAdminStatus watchedFolder,
        ResilienceSummary resilience,
        IntegrationSummary integrations,
        List<TechnicalExceptionItem> technicalExceptions) {

    public record ResilienceSummary(int maxAttempts, long initialBackoffMs,
                                    int packageProcessingMaxAttempts,
                                    boolean aiSecondaryEnabled, String aiSecondaryProvider,
                                    String aiSecondaryModel) {}

    public record IntegrationSummary(String deliveryType, boolean sdvConfigured,
                                     String identityMode,
                                     boolean productionIdentityConfigured) {}
}
