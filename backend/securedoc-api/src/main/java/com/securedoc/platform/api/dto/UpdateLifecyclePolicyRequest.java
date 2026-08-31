package com.securedoc.platform.api.dto;

public record UpdateLifecyclePolicyRequest(
        int originalRetentionHours,
        int redactedCacheHours,
        int neverExportedHoldHours,
        boolean finalApprovalRequired
) {
}
