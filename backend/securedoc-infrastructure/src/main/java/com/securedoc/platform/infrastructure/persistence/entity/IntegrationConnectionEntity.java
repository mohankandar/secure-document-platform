package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "integration_connections",
        uniqueConstraints = @UniqueConstraint(name = "uk_integration_connection_tenant_name", columnNames = {"tenant_id", "name"}))
public class IntegrationConnectionEntity {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;
    @Column(name = "name", nullable = false, length = 120)
    private String name;
    @Column(name = "description", length = 500)
    private String description;
    @Column(name = "purpose", nullable = false, length = 64)
    private String purpose;
    @Column(name = "direction", nullable = false, length = 32)
    private String direction;
    @Column(name = "provider_type", nullable = false, length = 64)
    private String providerType;
    @Column(name = "authentication_type", nullable = false, length = 64)
    private String authenticationType;
    @Column(name = "client_principal", length = 255)
    private String clientPrincipal;
    @Column(name = "endpoint", length = 1000)
    private String endpoint;
    @Column(name = "file_share_root", length = 1000)
    private String fileShareRoot;
    @Column(name = "secret_reference", length = 500)
    private String secretReference;
    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds;
    @Column(name = "retry_max_attempts", nullable = false)
    private int retryMaxAttempts;
    @Column(name = "incomplete_window_hours", nullable = false)
    private int incompleteWindowHours;
    @Column(name = "max_documents_per_intake", nullable = false)
    private int maxDocumentsPerIntake;
    @Column(name = "max_pdf_size_bytes", nullable = false)
    private long maxPdfSizeBytes;
    @Column(name = "max_pdf_pages", nullable = false)
    private int maxPdfPages;
    @Column(name = "polling_schedule_type", length = 32)
    private String pollingScheduleType;
    @Column(name = "polling_interval_minutes")
    private Integer pollingIntervalMinutes;
    @Column(name = "polling_local_time")
    private LocalTime pollingLocalTime;
    @Column(name = "polling_time_zone", length = 80)
    private String pollingTimeZone;
    @Column(name = "polling_batch_limit")
    private Integer pollingBatchLimit;
    @Column(name = "acknowledgement_mode", nullable = false, length = 64)
    private String acknowledgementMode;
    @Column(name = "enabled", nullable = false)
    private boolean enabled;
    @Column(name = "configuration_version", nullable = false)
    private int configurationVersion;
    @Column(name = "health_status", nullable = false, length = 32)
    private String healthStatus;
    @Column(name = "last_success_at")
    private Instant lastSuccessAt;
    @Column(name = "last_failure_at")
    private Instant lastFailureAt;
    @Column(name = "last_failure_category", length = 120)
    private String lastFailureCategory;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }
    public String getAuthenticationType() { return authenticationType; }
    public void setAuthenticationType(String authenticationType) { this.authenticationType = authenticationType; }
    public String getClientPrincipal() { return clientPrincipal; }
    public void setClientPrincipal(String clientPrincipal) { this.clientPrincipal = clientPrincipal; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getFileShareRoot() { return fileShareRoot; }
    public void setFileShareRoot(String fileShareRoot) { this.fileShareRoot = fileShareRoot; }
    public String getSecretReference() { return secretReference; }
    public void setSecretReference(String secretReference) { this.secretReference = secretReference; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getRetryMaxAttempts() { return retryMaxAttempts; }
    public void setRetryMaxAttempts(int retryMaxAttempts) { this.retryMaxAttempts = retryMaxAttempts; }
    public int getIncompleteWindowHours() { return incompleteWindowHours; }
    public void setIncompleteWindowHours(int incompleteWindowHours) { this.incompleteWindowHours = incompleteWindowHours; }
    public int getMaxDocumentsPerIntake() { return maxDocumentsPerIntake; }
    public void setMaxDocumentsPerIntake(int v) { maxDocumentsPerIntake = v; }
    public long getMaxPdfSizeBytes() { return maxPdfSizeBytes; }
    public void setMaxPdfSizeBytes(long v) { maxPdfSizeBytes = v; }
    public int getMaxPdfPages() { return maxPdfPages; }
    public void setMaxPdfPages(int v) { maxPdfPages = v; }
    public String getPollingScheduleType() { return pollingScheduleType; }
    public void setPollingScheduleType(String v) { pollingScheduleType = v; }
    public Integer getPollingIntervalMinutes() { return pollingIntervalMinutes; }
    public void setPollingIntervalMinutes(Integer v) { pollingIntervalMinutes = v; }
    public LocalTime getPollingLocalTime() { return pollingLocalTime; }
    public void setPollingLocalTime(LocalTime v) { pollingLocalTime = v; }
    public String getPollingTimeZone() { return pollingTimeZone; }
    public void setPollingTimeZone(String v) { pollingTimeZone = v; }
    public Integer getPollingBatchLimit() { return pollingBatchLimit; }
    public void setPollingBatchLimit(Integer v) { pollingBatchLimit = v; }
    public String getAcknowledgementMode() { return acknowledgementMode; }
    public void setAcknowledgementMode(String acknowledgementMode) { this.acknowledgementMode = acknowledgementMode; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getConfigurationVersion() { return configurationVersion; }
    public void setConfigurationVersion(int configurationVersion) { this.configurationVersion = configurationVersion; }
    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String healthStatus) { this.healthStatus = healthStatus; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public void setLastSuccessAt(Instant lastSuccessAt) { this.lastSuccessAt = lastSuccessAt; }
    public Instant getLastFailureAt() { return lastFailureAt; }
    public void setLastFailureAt(Instant lastFailureAt) { this.lastFailureAt = lastFailureAt; }
    public String getLastFailureCategory() { return lastFailureCategory; }
    public void setLastFailureCategory(String lastFailureCategory) { this.lastFailureCategory = lastFailureCategory; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
