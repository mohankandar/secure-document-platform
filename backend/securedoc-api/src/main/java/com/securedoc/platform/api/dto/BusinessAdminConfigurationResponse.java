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
            boolean maskSsnLastFour
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
            String description
    ) {}
    public record ReportingConfigurationResponse(
            List<ReportingDimensionResponse> dimensions,
            int deliveryTargetMinutes,
            double deliveryTargetPercent,
            double straightThroughTargetPercent,
            int reviewWaitTargetMinutes
    ) {}

    public record ReportingDimensionResponse(
            UUID definitionId, String key, String label, boolean enabled, int displayOrder
    ) {}

}
