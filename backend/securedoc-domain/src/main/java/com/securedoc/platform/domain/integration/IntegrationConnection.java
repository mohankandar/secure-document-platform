package com.securedoc.platform.domain.integration;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.Objects;

/**
 * Tenant-scoped technical integration definition.
 *
 * <p>No credential value is stored here. {@code secretReference} is an opaque deployment-managed
 * reference only and must never be returned as plaintext credential material.</p>
 */
public record IntegrationConnection(
        IntegrationConnectionId id,
        TenantId tenantId,
        String name,
        String description,
        IntegrationPurpose purpose,
        IntegrationDirection direction,
        IntegrationProviderType providerType,
        IntegrationAuthenticationType authenticationType,
        String clientPrincipal,
        String endpoint,
        String fileShareRoot,
        String secretReference,
        int timeoutSeconds,
        int retryMaxAttempts,
        int incompleteWindowHours,
        int maxDocumentsPerIntake,
        long maxPdfSizeBytes,
        int maxPdfPages,
        IntegrationPollingScheduleType pollingScheduleType,
        Integer pollingIntervalMinutes,
        LocalTime pollingLocalTime,
        String pollingTimeZone,
        Integer pollingBatchLimit,
        IntegrationAcknowledgementMode acknowledgementMode,
        boolean enabled,
        int configurationVersion,
        IntegrationHealthStatus healthStatus,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        String lastFailureCategory,
        Instant createdAt,
        Instant updatedAt) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 60;
    public static final int DEFAULT_RETRY_MAX_ATTEMPTS = 2;
    public static final int MAX_TIMEOUT_SECONDS = 600;
    public static final int MAX_RETRY_ATTEMPTS = 10;
    public static final int DEFAULT_INCOMPLETE_WINDOW_HOURS = 24;
    public static final int MAX_INCOMPLETE_WINDOW_HOURS = 7 * 24;
    public static final int DEFAULT_MAX_DOCUMENTS_PER_INTAKE = 100;
    public static final int MAX_DOCUMENTS_PER_INTAKE = 1000;
    public static final long DEFAULT_MAX_PDF_SIZE_BYTES = 250L * 1024L * 1024L;
    public static final long MAX_PDF_SIZE_BYTES = 2L * 1024L * 1024L * 1024L;
    public static final int DEFAULT_MAX_PDF_PAGES = 2000;
    public static final int MAX_PDF_PAGES = 10000;
    public static final int DEFAULT_POLLING_INTERVAL_MINUTES = 60;
    public static final int MIN_POLLING_INTERVAL_MINUTES = 5;
    public static final int MAX_POLLING_INTERVAL_MINUTES = 24 * 60;
    public static final int DEFAULT_POLLING_BATCH_LIMIT = 100;
    public static final int MAX_POLLING_BATCH_LIMIT = 1000;

    public IntegrationConnection {
        Objects.requireNonNull(id, "Integration connection id is required");
        Objects.requireNonNull(tenantId, "Tenant id is required");
        name = requireText(name, "Integration connection name", 120);
        description = normalizeOptional(description, 500);
        Objects.requireNonNull(purpose, "Integration purpose is required");
        Objects.requireNonNull(direction, "Integration direction is required");
        Objects.requireNonNull(providerType, "Integration provider type is required");
        Objects.requireNonNull(authenticationType, "Integration authentication type is required");
        clientPrincipal = normalizeOptional(clientPrincipal, 255);
        endpoint = normalizeOptional(endpoint, 1000);
        fileShareRoot = normalizeOptional(fileShareRoot, 1000);
        secretReference = normalizeOptional(secretReference, 500);
        if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("Integration timeout must be between 1 and " + MAX_TIMEOUT_SECONDS + " seconds");
        }
        if (retryMaxAttempts < 0 || retryMaxAttempts > MAX_RETRY_ATTEMPTS) {
            throw new IllegalArgumentException("Integration retry attempts must be between 0 and " + MAX_RETRY_ATTEMPTS);
        }
        if (incompleteWindowHours < 1 || incompleteWindowHours > MAX_INCOMPLETE_WINDOW_HOURS) {
            throw new IllegalArgumentException("Incomplete intake window must be between 1 and " + MAX_INCOMPLETE_WINDOW_HOURS + " hours");
        }
        if (maxDocumentsPerIntake < 1 || maxDocumentsPerIntake > MAX_DOCUMENTS_PER_INTAKE) {
            throw new IllegalArgumentException("Maximum documents per intake must be between 1 and " + MAX_DOCUMENTS_PER_INTAKE);
        }
        if (providerType == IntegrationProviderType.SCANNER && maxDocumentsPerIntake != 1) {
            throw new IllegalArgumentException("SCANNER intake requires exactly one consolidated PDF per intake");
        }
        if (maxPdfSizeBytes < 1 || maxPdfSizeBytes > MAX_PDF_SIZE_BYTES) {
            throw new IllegalArgumentException("Maximum PDF size must be between 1 and " + MAX_PDF_SIZE_BYTES + " bytes");
        }
        if (maxPdfPages < 1 || maxPdfPages > MAX_PDF_PAGES) {
            throw new IllegalArgumentException("Maximum PDF pages must be between 1 and " + MAX_PDF_PAGES);
        }
        pollingTimeZone = normalizeOptional(pollingTimeZone, 80);
        validatePollingSchedule(purpose, direction, providerType, pollingScheduleType, pollingIntervalMinutes, pollingLocalTime, pollingTimeZone, pollingBatchLimit);
        Objects.requireNonNull(acknowledgementMode, "Integration acknowledgement mode is required");
        if (configurationVersion < 1) throw new IllegalArgumentException("Configuration version must be >= 1");
        Objects.requireNonNull(healthStatus, "Integration health status is required");
        lastFailureCategory = normalizeOptional(lastFailureCategory, 120);
        Objects.requireNonNull(createdAt, "Created time is required");
        Objects.requireNonNull(updatedAt, "Updated time is required");
        validateCompatibility(purpose, direction, providerType, authenticationType, clientPrincipal, endpoint, fileShareRoot,
                secretReference, acknowledgementMode, enabled);
    }

    public static IntegrationConnection create(
            TenantId tenantId, String name, String description, IntegrationPurpose purpose,
            IntegrationDirection direction, IntegrationProviderType providerType,
            IntegrationAuthenticationType authenticationType, String clientPrincipal, String endpoint, String fileShareRoot,
            String secretReference, Integer timeoutSeconds, Integer retryMaxAttempts, Integer incompleteWindowHours,
            Integer maxDocumentsPerIntake, Long maxPdfSizeBytes, Integer maxPdfPages,
            IntegrationAcknowledgementMode acknowledgementMode, boolean enabled, Instant now) {
        return create(tenantId, name, description, purpose, direction, providerType, authenticationType, clientPrincipal, endpoint,
                fileShareRoot, secretReference, timeoutSeconds, retryMaxAttempts, incompleteWindowHours, maxDocumentsPerIntake,
                maxPdfSizeBytes, maxPdfPages, null, null, null, null, null, acknowledgementMode, enabled, now);
    }

    public static IntegrationConnection create(
            TenantId tenantId, String name, String description, IntegrationPurpose purpose,
            IntegrationDirection direction, IntegrationProviderType providerType,
            IntegrationAuthenticationType authenticationType, String clientPrincipal, String endpoint, String fileShareRoot,
            String secretReference, Integer timeoutSeconds, Integer retryMaxAttempts, Integer incompleteWindowHours,
            Integer maxDocumentsPerIntake, Long maxPdfSizeBytes, Integer maxPdfPages,
            IntegrationPollingScheduleType pollingScheduleType, Integer pollingIntervalMinutes, LocalTime pollingLocalTime,
            String pollingTimeZone, Integer pollingBatchLimit,
            IntegrationAcknowledgementMode acknowledgementMode, boolean enabled, Instant now) {
        return new IntegrationConnection(
                IntegrationConnectionId.generate(), tenantId, name, description, purpose, direction, providerType,
                authenticationType, clientPrincipal, endpoint, fileShareRoot, secretReference,
                timeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds,
                retryMaxAttempts == null ? DEFAULT_RETRY_MAX_ATTEMPTS : retryMaxAttempts,
                incompleteWindowHours == null ? DEFAULT_INCOMPLETE_WINDOW_HOURS : incompleteWindowHours,
                maxDocumentsPerIntake == null
                        ? (providerType == IntegrationProviderType.SCANNER ? 1 : DEFAULT_MAX_DOCUMENTS_PER_INTAKE)
                        : maxDocumentsPerIntake,
                maxPdfSizeBytes == null
                        ? (providerType == IntegrationProviderType.SCANNER ? MAX_PDF_SIZE_BYTES : DEFAULT_MAX_PDF_SIZE_BYTES)
                        : maxPdfSizeBytes,
                maxPdfPages == null
                        ? (providerType == IntegrationProviderType.SCANNER ? MAX_PDF_PAGES : DEFAULT_MAX_PDF_PAGES)
                        : maxPdfPages,
                pollingScheduleType, pollingIntervalMinutes, pollingLocalTime, pollingTimeZone, pollingBatchLimit,
                acknowledgementMode == null ? IntegrationAcknowledgementMode.NONE : acknowledgementMode,
                enabled, 1, enabled ? IntegrationHealthStatus.UNKNOWN : IntegrationHealthStatus.DISABLED,
                null, null, null, now, now);
    }

    public IntegrationConnection updateConfiguration(
            String name, String description, IntegrationPurpose purpose, IntegrationDirection direction,
            IntegrationProviderType providerType, IntegrationAuthenticationType authenticationType,
            String clientPrincipal, String endpoint, String fileShareRoot, String newSecretReference, boolean preserveSecretReference,
            Integer timeoutSeconds, Integer retryMaxAttempts, Integer incompleteWindowHours, Integer maxDocumentsPerIntake,
            Long maxPdfSizeBytes, Integer maxPdfPages, IntegrationPollingScheduleType pollingScheduleType,
            Integer pollingIntervalMinutes, LocalTime pollingLocalTime, String pollingTimeZone, Integer pollingBatchLimit,
            IntegrationAcknowledgementMode acknowledgementMode, Instant now) {
        String effectiveSecretReference = preserveSecretReference ? secretReference : newSecretReference;
        return new IntegrationConnection(id, tenantId, name, description, purpose, direction, providerType,
                authenticationType, clientPrincipal, endpoint, fileShareRoot, effectiveSecretReference,
                timeoutSeconds == null ? this.timeoutSeconds : timeoutSeconds,
                retryMaxAttempts == null ? this.retryMaxAttempts : retryMaxAttempts,
                incompleteWindowHours == null ? this.incompleteWindowHours : incompleteWindowHours,
                maxDocumentsPerIntake == null ? this.maxDocumentsPerIntake : maxDocumentsPerIntake,
                maxPdfSizeBytes == null ? this.maxPdfSizeBytes : maxPdfSizeBytes,
                maxPdfPages == null ? this.maxPdfPages : maxPdfPages,
                pollingScheduleType, pollingIntervalMinutes, pollingLocalTime, pollingTimeZone, pollingBatchLimit,
                acknowledgementMode == null ? this.acknowledgementMode : acknowledgementMode,
                enabled, configurationVersion + 1, enabled ? IntegrationHealthStatus.UNKNOWN : IntegrationHealthStatus.DISABLED,
                lastSuccessAt, lastFailureAt, lastFailureCategory, createdAt, now);
    }

    public IntegrationConnection setEnabled(boolean enabled, Instant now) {
        return new IntegrationConnection(id, tenantId, name, description, purpose, direction, providerType,
                authenticationType, clientPrincipal, endpoint, fileShareRoot, secretReference, timeoutSeconds, retryMaxAttempts,
                incompleteWindowHours, maxDocumentsPerIntake, maxPdfSizeBytes, maxPdfPages, pollingScheduleType, pollingIntervalMinutes,
                pollingLocalTime, pollingTimeZone, pollingBatchLimit, acknowledgementMode, enabled, configurationVersion + 1,
                enabled ? IntegrationHealthStatus.UNKNOWN : IntegrationHealthStatus.DISABLED,
                lastSuccessAt, lastFailureAt, lastFailureCategory, createdAt, now);
    }

    public IntegrationConnection recordOperationalSuccess(Instant now) {
        return new IntegrationConnection(id, tenantId, name, description, purpose, direction, providerType, authenticationType,
                clientPrincipal, endpoint, fileShareRoot, secretReference, timeoutSeconds, retryMaxAttempts, incompleteWindowHours,
                maxDocumentsPerIntake, maxPdfSizeBytes, maxPdfPages, pollingScheduleType, pollingIntervalMinutes, pollingLocalTime,
                pollingTimeZone, pollingBatchLimit, acknowledgementMode, enabled, configurationVersion,
                enabled ? IntegrationHealthStatus.HEALTHY : IntegrationHealthStatus.DISABLED, now, lastFailureAt, null, createdAt, now);
    }

    public IntegrationConnection recordOperationalFailure(String failureCategory, Instant now) {
        return new IntegrationConnection(id, tenantId, name, description, purpose, direction, providerType, authenticationType,
                clientPrincipal, endpoint, fileShareRoot, secretReference, timeoutSeconds, retryMaxAttempts, incompleteWindowHours,
                maxDocumentsPerIntake, maxPdfSizeBytes, maxPdfPages, pollingScheduleType, pollingIntervalMinutes, pollingLocalTime,
                pollingTimeZone, pollingBatchLimit, acknowledgementMode, enabled, configurationVersion,
                enabled ? IntegrationHealthStatus.DEGRADED : IntegrationHealthStatus.DISABLED, lastSuccessAt, now, failureCategory, createdAt, now);
    }

    public boolean pollingSource() {
        return purpose == IntegrationPurpose.INGESTION_SOURCE && direction == IntegrationDirection.OUTBOUND
                && providerType == IntegrationProviderType.HTTP_API;
    }

    public boolean secretConfigured() { return secretReference != null && !secretReference.isBlank(); }

    private static void validateCompatibility(
            IntegrationPurpose purpose, IntegrationDirection direction, IntegrationProviderType providerType,
            IntegrationAuthenticationType authenticationType, String clientPrincipal, String endpoint, String fileShareRoot,
            String secretReference, IntegrationAcknowledgementMode acknowledgementMode, boolean enabled) {
        if (providerType != IntegrationProviderType.PUSH_API && providerType != IntegrationProviderType.SCANNER && clientPrincipal != null) {
            throw new IllegalArgumentException("Authenticated client principal binding is only valid for PUSH_API or SCANNER sources");
        }

        switch (providerType) {
            case PUSH_API -> {
                if (direction != IntegrationDirection.INBOUND || purpose != IntegrationPurpose.INGESTION_SOURCE) {
                    throw new IllegalArgumentException("PUSH_API is only valid for inbound ingestion sources");
                }
                if (endpoint != null || fileShareRoot != null) {
                    throw new IllegalArgumentException("PUSH_API does not accept endpoint or file-share path configuration");
                }
                if (enabled && clientPrincipal == null) {
                    throw new IllegalArgumentException("Enabled PUSH_API source requires an authenticated client principal binding");
                }
            }
            case WATCHED_FOLDER -> {
                if (direction != IntegrationDirection.INBOUND || purpose != IntegrationPurpose.INGESTION_SOURCE) {
                    throw new IllegalArgumentException("WATCHED_FOLDER is only valid for inbound ingestion sources");
                }
                if (clientPrincipal != null) {
                    throw new IllegalArgumentException("WATCHED_FOLDER does not use an authenticated client principal binding");
                }
            }
            case SCANNER -> {
                if (direction != IntegrationDirection.INBOUND || purpose != IntegrationPurpose.INGESTION_SOURCE) {
                    throw new IllegalArgumentException("SCANNER is only valid for inbound ingestion sources");
                }
                if (endpoint != null || fileShareRoot != null) {
                    throw new IllegalArgumentException("SCANNER does not accept endpoint or file-share path configuration");
                }
                if (acknowledgementMode != IntegrationAcknowledgementMode.NONE) {
                    throw new IllegalArgumentException("SCANNER source uses LexaVault intake reconciliation and must not configure a separate acknowledgement mode");
                }
                if (enabled && clientPrincipal == null) {
                    throw new IllegalArgumentException("Enabled SCANNER source requires an authenticated client principal binding");
                }
            }
            case HTTP_API -> {
                if (direction != IntegrationDirection.OUTBOUND) {
                    throw new IllegalArgumentException("HTTP_API connections must be outbound from LexaVault");
                }
                if (endpoint == null) throw new IllegalArgumentException("HTTP_API endpoint is required");
                if (fileShareRoot != null) throw new IllegalArgumentException("HTTP_API connection cannot define a file-share root");
                if (purpose == IntegrationPurpose.INGESTION_SOURCE
                        && acknowledgementMode != IntegrationAcknowledgementMode.SOURCE_PICKUP_ACK) {
                    throw new IllegalArgumentException("Polling HTTP ingestion sources must use source-pickup acknowledgement");
                }
                if (purpose == IntegrationPurpose.INGESTION_SOURCE
                        && (authenticationType == IntegrationAuthenticationType.OAUTH2_CLIENT_CREDENTIALS
                        || authenticationType == IntegrationAuthenticationType.MTLS)) {
                    throw new IllegalArgumentException("V1 polling HTTP sources support NONE, BASIC, API_KEY, or JWT authentication; OAuth2/mTLS require a provider-specific connector");
                }
            }
            case FILE_SHARE -> {
                if (direction != IntegrationDirection.OUTBOUND || purpose != IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY) {
                    throw new IllegalArgumentException("FILE_SHARE is only valid for outbound derived-document delivery in V1");
                }
                if (fileShareRoot == null) throw new IllegalArgumentException("FILE_SHARE root path is required");
                if (endpoint != null) throw new IllegalArgumentException("FILE_SHARE connection cannot define an HTTP endpoint");
                if (authenticationType != IntegrationAuthenticationType.NONE) {
                    throw new IllegalArgumentException("FILE_SHARE authentication is provided by the deployment/service identity in V1");
                }
            }
        }

        if (purpose == IntegrationPurpose.SDV_DELIVERY && providerType != IntegrationProviderType.HTTP_API) {
            throw new IllegalArgumentException("SDV delivery requires HTTP_API provider in V1");
        }
        if (purpose == IntegrationPurpose.SDV_DELIVERY
                && (authenticationType == IntegrationAuthenticationType.OAUTH2_CLIENT_CREDENTIALS
                || authenticationType == IntegrationAuthenticationType.MTLS)) {
            throw new IllegalArgumentException("V1 SDV delivery supports NONE, BASIC, API_KEY, or JWT authentication; OAuth2/mTLS require a provider-specific connector");
        }
        if (purpose == IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY
                && providerType != IntegrationProviderType.HTTP_API
                && providerType != IntegrationProviderType.FILE_SHARE) {
            throw new IllegalArgumentException("Derived-document delivery requires HTTP_API or FILE_SHARE provider");
        }
        if (purpose == IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY
                && providerType == IntegrationProviderType.HTTP_API
                && acknowledgementMode != IntegrationAcknowledgementMode.DESTINATION_RECEIPT) {
            throw new IllegalArgumentException("Derived HTTP delivery must use destination-receipt acknowledgement");
        }
        if (purpose == IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY
                && providerType == IntegrationProviderType.HTTP_API
                && (authenticationType == IntegrationAuthenticationType.OAUTH2_CLIENT_CREDENTIALS
                || authenticationType == IntegrationAuthenticationType.MTLS)) {
            throw new IllegalArgumentException("V1 derived HTTP delivery supports NONE, BASIC, API_KEY, or JWT authentication; OAuth2/mTLS require a provider-specific connector");
        }
        if (direction == IntegrationDirection.OUTBOUND && authenticationType != IntegrationAuthenticationType.NONE && enabled
                && (secretReference == null || secretReference.isBlank())) {
            throw new IllegalArgumentException("Enabled authenticated outbound integration requires a protected secret reference");
        }
        if (providerType == IntegrationProviderType.FILE_SHARE
                && acknowledgementMode != IntegrationAcknowledgementMode.DURABLE_FILE_WRITE) {
            throw new IllegalArgumentException("FILE_SHARE delivery must use durable-file-write acknowledgement");
        }
        if (purpose == IntegrationPurpose.SDV_DELIVERY
                && acknowledgementMode != IntegrationAcknowledgementMode.SDV_SUBMISSION_ACK) {
            throw new IllegalArgumentException("SDV delivery must use SDV submission acknowledgement");
        }
    }

    private static void validatePollingSchedule(IntegrationPurpose purpose, IntegrationDirection direction,
                                                IntegrationProviderType providerType, IntegrationPollingScheduleType scheduleType,
                                                Integer intervalMinutes, LocalTime localTime, String timeZone, Integer batchLimit) {
        boolean pollingSource = purpose == IntegrationPurpose.INGESTION_SOURCE
                && direction == IntegrationDirection.OUTBOUND && providerType == IntegrationProviderType.HTTP_API;
        if (!pollingSource) {
            if (scheduleType != null || intervalMinutes != null || localTime != null || timeZone != null || batchLimit != null) {
                throw new IllegalArgumentException("Polling schedule configuration is only valid for outbound HTTP ingestion sources");
            }
            return;
        }
        if (scheduleType == null) throw new IllegalArgumentException("Polling schedule type is required for polling sources");
        int effectiveBatchLimit = batchLimit == null ? DEFAULT_POLLING_BATCH_LIMIT : batchLimit;
        if (effectiveBatchLimit < 1 || effectiveBatchLimit > MAX_POLLING_BATCH_LIMIT) {
            throw new IllegalArgumentException("Polling batch limit must be between 1 and " + MAX_POLLING_BATCH_LIMIT);
        }
        if (scheduleType == IntegrationPollingScheduleType.INTERVAL) {
            int effectiveInterval = intervalMinutes == null ? DEFAULT_POLLING_INTERVAL_MINUTES : intervalMinutes;
            if (effectiveInterval < MIN_POLLING_INTERVAL_MINUTES || effectiveInterval > MAX_POLLING_INTERVAL_MINUTES) {
                throw new IllegalArgumentException("Polling interval must be between " + MIN_POLLING_INTERVAL_MINUTES + " and " + MAX_POLLING_INTERVAL_MINUTES + " minutes");
            }
            if (localTime != null || timeZone != null) {
                throw new IllegalArgumentException("Interval polling does not use local time or time zone");
            }
        } else if (scheduleType == IntegrationPollingScheduleType.DAILY) {
            if (localTime == null) throw new IllegalArgumentException("Daily polling requires a local time");
            if (timeZone == null) throw new IllegalArgumentException("Daily polling requires a time zone");
            try { ZoneId.of(timeZone); } catch (DateTimeException e) {
                throw new IllegalArgumentException("Polling time zone must be a valid IANA time zone");
            }
            if (intervalMinutes != null) throw new IllegalArgumentException("Daily polling does not use an interval");
        }
    }

    private static String requireText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(label + " exceeds " + maxLength + " characters");
        return normalized;
    }

    private static String normalizeOptional(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException("Value exceeds " + maxLength + " characters");
        return normalized;
    }
}
