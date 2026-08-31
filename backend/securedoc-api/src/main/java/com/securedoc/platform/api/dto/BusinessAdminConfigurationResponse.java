package com.securedoc.platform.api.dto;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record BusinessAdminConfigurationResponse(
        List<BusinessIdentifierAdminResponse> identifiers,
        List<DashboardAdminColumnResponse> dashboardColumns,
        SensitiveDataPolicyResponse sensitiveDataPolicy,
        LifecyclePolicyResponse lifecyclePolicy
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
            int displayOrder
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
}
