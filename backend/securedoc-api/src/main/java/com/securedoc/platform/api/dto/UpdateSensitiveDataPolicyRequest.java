package com.securedoc.platform.api.dto;

import java.util.Set;

public record UpdateSensitiveDataPolicyRequest(
        Set<String> enabledEntityTypes,
        double autoRedactThreshold,
        String reviewMode,
        Boolean maskSsnLastFour,
        /* Task 0044: null keeps the stored value. */
        Boolean searchableRedactedPages,
        /* Task 0049: null keeps the stored value. */
        Integer pageBatchSize,
        Integer pageBatchOverlap,
        /* Task 0062: regulation packs in force; null keeps the stored ones. */
        Set<String> enabledPacks,
        /* Task 0062: kinds whose last four digits stay visible; null keeps the stored ones. */
        Set<String> keepLastFourKinds,
        /* Task 0064: null keeps the stored value. */
        Boolean visionEnabled,
        Double visionOcrThreshold,
        /* Task 0083: null keeps the stored value (a pack newly turned on can turn it on). */
        Boolean secondCheckEnabled,
        /* Task 0065: null keeps the stored value. */
        Boolean documentContextEnabled
) {}
