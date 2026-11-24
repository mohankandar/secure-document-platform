package com.securedoc.platform.api.dto;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record BusinessAdminConfigurationResponse(
        List<BusinessIdentifierAdminResponse> identifiers,
        List<DashboardAdminColumnResponse> dashboardColumns,
        SensitiveDataPolicyResponse sensitiveDataPolicy,
        LifecyclePolicyResponse lifecyclePolicy,
        DocumentIntelligenceResponse documentIntelligence,
        ReportingConfigurationResponse reportingConfiguration
) {
    public record BusinessIdentifierAdminResponse(
            UUID id,
            String key,
            String label,
            boolean required,
            boolean primary,
            boolean searchable,
            boolean extractable,
            boolean displayable,
            int displayOrder,
            String validationType,
            Integer exactLength,
            Integer minLength,
            Integer maxLength,
            String caseNormalization,
            boolean stripSeparators,
            Set<String> allowedValues,
            String customPattern
    ) {}

    public record DashboardAdminColumnResponse(
            String key,
            String label,
            String kind,
            boolean primary,
            boolean allowed,
            boolean defaultVisible,
            int displayOrder
    ) {}

    public record SensitiveDataPolicyResponse(
            Set<String> enabledEntityTypes,
            double autoRedactThreshold,
            String reviewMode,
            boolean maskSsnLastFour,
            /* Task 0044: invisible OCR text on redacted pages (on by default). */
            boolean searchableRedactedPages,
            /* Task 0049: page batches of large documents (defaults 20 pages, 2 shared). */
            int pageBatchSize,
            int pageBatchOverlap,
            /* Task 0064: routed pages are read by the vision model (on by default), and the routing threshold. */
            boolean visionEnabled,
            double visionOcrThreshold
    ) {}

    public record LifecyclePolicyResponse(
            int originalRetentionHours,
            int redactedCacheHours,
            int neverExportedHoldHours,
            boolean finalApprovalRequired,
            int maximumSuccessfulExportCycles,
            int maximumRedactedCacheHours
    ) {}

    public record DocumentIntelligenceResponse(
            String mode,
            String activeBundleVersion,
            String lastKnownGoodBundleVersion,
            int configurationVersion,
            boolean baselineDiscoveryRequired,
            List<IntelligenceBundleResponse> availableBundles
    ) {}

    public record IntelligenceBundleResponse(
            String version,
            String status,
            String modelVersion,
            String promptVersion,
            String detectorVersion,
            String ocrRulesVersion,
            String profileVersion,
            String validationRulesVersion,
            String thresholdsVersion,
            String description,
            /** When the bundle was released (promoted); null for a bundle that never was. */
            java.time.Instant promotedAt
    ) {}
    public record ReportingConfigurationResponse(
            List<ReportingDimensionResponse> dimensions,
            int deliveryTargetMinutes,
            double deliveryTargetPercent,
            double straightThroughTargetPercent,
            int reviewWaitTargetMinutes,
            int reviewWaitTargetPercent,
            double minutesPerPage
    ) {}

    public record ReportingDimensionResponse(
            UUID definitionId, String key, String label, boolean enabled, int displayOrder
    ) {}

}
