package com.securedoc.platform.api.integration;

public record IntegrationConnectionRequest(
        String name,
        String description,
        String purpose,
        String direction,
        String providerType,
        String authenticationType,
        String clientPrincipal,
        String endpoint,
        String fileShareRoot,
        String secretReference,
        Boolean clearSecretReference,
        Integer timeoutSeconds,
        Integer retryMaxAttempts,
        Integer incompleteWindowHours,
        Integer maxDocumentsPerIntake,
        Long maxPdfSizeBytes,
        Integer maxPdfPages,
        String pollingScheduleType,
        Integer pollingIntervalMinutes,
        String pollingLocalTime,
        String pollingTimeZone,
        Integer pollingBatchLimit,
        String acknowledgementMode,
        Boolean enabled) {
}
