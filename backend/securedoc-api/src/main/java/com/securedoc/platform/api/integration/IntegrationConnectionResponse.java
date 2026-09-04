package com.securedoc.platform.api.integration;

import java.time.Instant;
import java.util.UUID;

public record IntegrationConnectionResponse(
        UUID connectionId,
        String name,
        String description,
        String purpose,
        String direction,
        String providerType,
        String authenticationType,
        String clientPrincipal,
        String endpoint,
        String fileShareRoot,
        boolean secretConfigured,
        int timeoutSeconds,
        int retryMaxAttempts,
        int incompleteWindowHours,
        int maxDocumentsPerIntake,
        long maxPdfSizeBytes,
        int maxPdfPages,
        String pollingScheduleType,
        Integer pollingIntervalMinutes,
        String pollingLocalTime,
        String pollingTimeZone,
        Integer pollingBatchLimit,
        String acknowledgementMode,
        boolean enabled,
        int configurationVersion,
        String healthStatus,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        String lastFailureCategory,
        Instant createdAt,
        Instant updatedAt) {
}
