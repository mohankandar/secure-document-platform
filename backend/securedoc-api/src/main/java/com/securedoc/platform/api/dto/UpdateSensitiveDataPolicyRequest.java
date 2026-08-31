package com.securedoc.platform.api.dto;

import java.util.Set;

public record UpdateSensitiveDataPolicyRequest(
        Set<String> enabledEntityTypes,
        double autoRedactThreshold,
        String reviewMode,
        Boolean maskSsnLastFour
) {}
