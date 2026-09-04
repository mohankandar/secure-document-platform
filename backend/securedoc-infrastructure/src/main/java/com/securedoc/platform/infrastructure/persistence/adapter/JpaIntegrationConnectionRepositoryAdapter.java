package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.*;
import com.securedoc.platform.infrastructure.persistence.entity.IntegrationConnectionEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataIntegrationConnectionRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class JpaIntegrationConnectionRepositoryAdapter implements IntegrationConnectionRepository {
    private final SpringDataIntegrationConnectionRepository repository;

    public JpaIntegrationConnectionRepositoryAdapter(SpringDataIntegrationConnectionRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<IntegrationConnection> findByTenantId(TenantId tenantId) {
        return repository.findByTenantIdOrderByNameAsc(tenantId.value()).stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<IntegrationConnection> findByTenantIdAndId(TenantId tenantId, IntegrationConnectionId id) {
        return repository.findByIdAndTenantId(id.value(), tenantId.value()).map(this::toDomain);
    }

    @Override
    public Optional<IntegrationConnection> findEnabledPushSourceByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal) {
        return repository.findByTenantIdAndClientPrincipalAndProviderTypeAndEnabledTrue(
                tenantId.value(), clientPrincipal, IntegrationProviderType.PUSH_API.name()).map(this::toDomain);
    }

    @Override
    public Optional<IntegrationConnection> findEnabledSdvDeliveryByTenantId(TenantId tenantId) {
        List<IntegrationConnectionEntity> matches = repository.findByTenantIdAndPurposeAndDirectionAndProviderTypeAndEnabledTrueOrderByNameAsc(
                tenantId.value(), IntegrationPurpose.SDV_DELIVERY.name(), IntegrationDirection.OUTBOUND.name(),
                IntegrationProviderType.HTTP_API.name());
        if (matches.size() > 1) {
            throw new IllegalStateException("Tenant has more than one enabled SDV delivery connection");
        }
        return matches.stream().findFirst().map(this::toDomain);
    }

    @Override
    public List<IntegrationConnection> findEnabledPollingSources() {
        return repository.findByPurposeAndDirectionAndProviderTypeAndEnabledTrueOrderByNameAsc(
                IntegrationPurpose.INGESTION_SOURCE.name(), IntegrationDirection.OUTBOUND.name(),
                IntegrationProviderType.HTTP_API.name()).stream().map(this::toDomain).toList();
    }

    @Override
    public boolean existsByTenantIdAndNameIgnoreCase(TenantId tenantId, String name, IntegrationConnectionId excludeId) {
        return excludeId == null
                ? repository.existsByTenantIdAndNameIgnoreCase(tenantId.value(), name)
                : repository.existsByTenantIdAndNameIgnoreCaseAndIdNot(tenantId.value(), name, excludeId.value());
    }

    @Override
    public boolean existsByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal, IntegrationConnectionId excludeId) {
        if (clientPrincipal == null || clientPrincipal.isBlank()) return false;
        return excludeId == null
                ? repository.existsByTenantIdAndClientPrincipal(tenantId.value(), clientPrincipal)
                : repository.existsByTenantIdAndClientPrincipalAndIdNot(tenantId.value(), clientPrincipal, excludeId.value());
    }

    @Override
    public void save(IntegrationConnection connection) {
        IntegrationConnectionEntity entity = repository.findById(connection.id().value())
                .orElseGet(IntegrationConnectionEntity::new);
        entity.setId(connection.id().value());
        entity.setTenantId(connection.tenantId().value());
        entity.setName(connection.name());
        entity.setDescription(connection.description());
        entity.setPurpose(connection.purpose().name());
        entity.setDirection(connection.direction().name());
        entity.setProviderType(connection.providerType().name());
        entity.setAuthenticationType(connection.authenticationType().name());
        entity.setClientPrincipal(connection.clientPrincipal());
        entity.setEndpoint(connection.endpoint());
        entity.setFileShareRoot(connection.fileShareRoot());
        entity.setSecretReference(connection.secretReference());
        entity.setTimeoutSeconds(connection.timeoutSeconds());
        entity.setRetryMaxAttempts(connection.retryMaxAttempts());
        entity.setIncompleteWindowHours(connection.incompleteWindowHours());
        entity.setMaxDocumentsPerIntake(connection.maxDocumentsPerIntake());
        entity.setMaxPdfSizeBytes(connection.maxPdfSizeBytes());
        entity.setMaxPdfPages(connection.maxPdfPages());
        entity.setPollingScheduleType(connection.pollingScheduleType() == null ? null : connection.pollingScheduleType().name());
        entity.setPollingIntervalMinutes(connection.pollingIntervalMinutes());
        entity.setPollingLocalTime(connection.pollingLocalTime());
        entity.setPollingTimeZone(connection.pollingTimeZone());
        entity.setPollingBatchLimit(connection.pollingBatchLimit());
        entity.setAcknowledgementMode(connection.acknowledgementMode().name());
        entity.setEnabled(connection.enabled());
        entity.setConfigurationVersion(connection.configurationVersion());
        entity.setHealthStatus(connection.healthStatus().name());
        entity.setLastSuccessAt(connection.lastSuccessAt());
        entity.setLastFailureAt(connection.lastFailureAt());
        entity.setLastFailureCategory(connection.lastFailureCategory());
        entity.setCreatedAt(connection.createdAt());
        entity.setUpdatedAt(connection.updatedAt());
        repository.save(entity);
    }

    private IntegrationConnection toDomain(IntegrationConnectionEntity entity) {
        return new IntegrationConnection(
                IntegrationConnectionId.of(entity.getId()), TenantId.of(entity.getTenantId()), entity.getName(),
                entity.getDescription(), IntegrationPurpose.valueOf(entity.getPurpose()),
                IntegrationDirection.valueOf(entity.getDirection()), IntegrationProviderType.valueOf(entity.getProviderType()),
                IntegrationAuthenticationType.valueOf(entity.getAuthenticationType()), entity.getClientPrincipal(), entity.getEndpoint(),
                entity.getFileShareRoot(), entity.getSecretReference(), entity.getTimeoutSeconds(),
                entity.getRetryMaxAttempts(), entity.getIncompleteWindowHours(), entity.getMaxDocumentsPerIntake(), entity.getMaxPdfSizeBytes(), entity.getMaxPdfPages(),
                entity.getPollingScheduleType() == null ? null : IntegrationPollingScheduleType.valueOf(entity.getPollingScheduleType()),
                entity.getPollingIntervalMinutes(), entity.getPollingLocalTime(), entity.getPollingTimeZone(), entity.getPollingBatchLimit(),
                IntegrationAcknowledgementMode.valueOf(entity.getAcknowledgementMode()),
                entity.isEnabled(), entity.getConfigurationVersion(), IntegrationHealthStatus.valueOf(entity.getHealthStatus()),
                entity.getLastSuccessAt(), entity.getLastFailureAt(), entity.getLastFailureCategory(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
