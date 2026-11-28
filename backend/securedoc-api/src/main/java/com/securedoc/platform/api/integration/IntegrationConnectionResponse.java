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
        Instant updatedAt,
        com.securedoc.platform.domain.integration.DestinationSettings destination,
        /* Task 0110: host names allowed to be internal or plain http (by name only). */
        java.util.Set<String> allowedHosts) {
}
