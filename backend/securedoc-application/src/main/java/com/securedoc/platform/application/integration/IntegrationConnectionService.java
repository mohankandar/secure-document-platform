package com.securedoc.platform.application.integration;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/** Tenant-scoped management service for technical integration connections. */
public final class IntegrationConnectionService {
    private final IntegrationConnectionRepository repository;
    private final AuditService auditService;
    private final Clock clock;

    public IntegrationConnectionService(IntegrationConnectionRepository repository, AuditService auditService) {
        this(repository, auditService, Clock.systemUTC());
    }

    IntegrationConnectionService(IntegrationConnectionRepository repository, AuditService auditService, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.auditService = Objects.requireNonNull(auditService);
        this.clock = Objects.requireNonNull(clock);
    }

    public List<IntegrationConnection> list(TenantId tenantId) {
        return repository.findByTenantId(Objects.requireNonNull(tenantId));
    }

    public IntegrationConnection get(TenantId tenantId, IntegrationConnectionId id) {
        return repository.findByTenantIdAndId(Objects.requireNonNull(tenantId), Objects.requireNonNull(id))
                .orElseThrow(() -> new NotFoundException("IntegrationConnection", id.toString()));
    }

    public IntegrationConnection create(TenantId tenantId, ActorId actorId, CorrelationId correlationId,
                                        ConnectionInput input) {
        Objects.requireNonNull(input, "Integration connection input is required");
        ensureUniqueName(tenantId, input.name(), null);
        ensureUniqueClientPrincipal(tenantId, input, null);
        ensureSingleEnabledSdv(tenantId, input.purpose(), input.enabled(), null);
        IntegrationConnection connection;
        try {
            connection = IntegrationConnection.create(tenantId, input.name(), input.description(), input.purpose(),
                    input.direction(), input.providerType(), input.authenticationType(), input.clientPrincipal(), input.endpoint(),
                    input.fileShareRoot(), input.secretReference(), input.timeoutSeconds(), input.retryMaxAttempts(), input.incompleteWindowHours(), input.maxDocumentsPerIntake(), input.maxPdfSizeBytes(), input.maxPdfPages(),
                    input.pollingScheduleType(), input.pollingIntervalMinutes(), input.pollingLocalTime(), input.pollingTimeZone(), input.pollingBatchLimit(),
                    input.acknowledgementMode(), input.enabled(), Instant.now(clock));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(e.getMessage());
        }
        repository.save(connection);
        auditService.recordSuccess(tenantId, actorId, AuditAction.INTEGRATION_CONNECTION_CREATED,
                "IntegrationConnection", connection.id().toString(), correlationId,
                safeAuditDetail(connection));
        return connection;
    }

    public IntegrationConnection update(TenantId tenantId, IntegrationConnectionId id,
                                        ActorId actorId, CorrelationId correlationId, ConnectionInput input) {
        Objects.requireNonNull(input, "Integration connection input is required");
        IntegrationConnection existing = get(tenantId, id);
        ensureUniqueName(tenantId, input.name(), id);
        ensureUniqueClientPrincipal(tenantId, input, id);
        ensureSingleEnabledSdv(tenantId, input.purpose(), existing.enabled(), id);
        IntegrationConnection updated;
        try {
            updated = existing.updateConfiguration(input.name(), input.description(), input.purpose(), input.direction(),
                    input.providerType(), input.authenticationType(), input.clientPrincipal(), input.endpoint(), input.fileShareRoot(),
                    input.secretReference(), input.preserveSecretReference(), input.timeoutSeconds(), input.retryMaxAttempts(), input.incompleteWindowHours(), input.maxDocumentsPerIntake(), input.maxPdfSizeBytes(), input.maxPdfPages(),
                    input.pollingScheduleType(), input.pollingIntervalMinutes(), input.pollingLocalTime(), input.pollingTimeZone(), input.pollingBatchLimit(),
                    input.acknowledgementMode(), Instant.now(clock));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(e.getMessage());
        }
        repository.save(updated);
        auditService.recordSuccess(tenantId, actorId, AuditAction.INTEGRATION_CONNECTION_UPDATED,
                "IntegrationConnection", updated.id().toString(), correlationId,
                safeAuditDetail(updated));
        return updated;
    }

    public IntegrationConnection setEnabled(TenantId tenantId, IntegrationConnectionId id, boolean enabled,
                                            ActorId actorId, CorrelationId correlationId) {
        IntegrationConnection existing = get(tenantId, id);
        if (existing.enabled() == enabled) return existing;
        ensureSingleEnabledSdv(tenantId, existing.purpose(), enabled, id);
        IntegrationConnection updated;
        try {
            updated = existing.setEnabled(enabled, Instant.now(clock));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(e.getMessage());
        }
        repository.save(updated);
        auditService.recordSuccess(tenantId, actorId,
                enabled ? AuditAction.INTEGRATION_CONNECTION_ENABLED : AuditAction.INTEGRATION_CONNECTION_DISABLED,
                "IntegrationConnection", updated.id().toString(), correlationId,
                safeAuditDetail(updated));
        return updated;
    }


    public List<IntegrationConnection> listEnabledPollingSources() {
        return repository.findEnabledPollingSources();
    }

    public IntegrationConnection recordOperationalSuccess(IntegrationConnection connection) {
        IntegrationConnection updated = connection.recordOperationalSuccess(Instant.now(clock));
        repository.save(updated);
        return updated;
    }

    public IntegrationConnection recordOperationalFailure(IntegrationConnection connection, String category) {
        IntegrationConnection updated = connection.recordOperationalFailure(category, Instant.now(clock));
        repository.save(updated);
        return updated;
    }

    private void ensureUniqueName(TenantId tenantId, String name, IntegrationConnectionId excludeId) {
        if (name == null || name.isBlank()) return;
        if (repository.existsByTenantIdAndNameIgnoreCase(tenantId, name.trim(), excludeId)) {
            throw new ValidationException("Integration connection name already exists for this tenant");
        }
    }

    public IntegrationConnection resolveEnabledPushSource(TenantId tenantId, String clientPrincipal) {
        if (clientPrincipal == null || clientPrincipal.isBlank()) {
            throw new ValidationException("Authenticated integration client principal is required");
        }
        return repository.findEnabledPushSourceByTenantIdAndClientPrincipal(tenantId, clientPrincipal.trim())
                .orElseThrow(() -> new com.securedoc.platform.application.exception.AccessDeniedException(
                        "Authenticated client is not bound to an enabled push-ingestion source"));
    }

    public IntegrationConnection resolveEnabledScannerSource(TenantId tenantId, String clientPrincipal) {
        if (clientPrincipal == null || clientPrincipal.isBlank()) {
            throw new ValidationException("Authenticated scanner client principal is required");
        }
        return repository.findEnabledScannerSourceByTenantIdAndClientPrincipal(tenantId, clientPrincipal.trim())
                .orElseThrow(() -> new com.securedoc.platform.application.exception.AccessDeniedException(
                        "Authenticated client is not bound to an enabled scanner-ingestion source"));
    }

    private void ensureUniqueClientPrincipal(TenantId tenantId, ConnectionInput input, IntegrationConnectionId excludeId) {
        if ((input.providerType() != IntegrationProviderType.PUSH_API && input.providerType() != IntegrationProviderType.SCANNER)
                || input.clientPrincipal() == null || input.clientPrincipal().isBlank()) return;
        if (repository.existsByTenantIdAndClientPrincipal(tenantId, input.clientPrincipal().trim(), excludeId)) {
            throw new ValidationException("Authenticated client principal is already bound to another tenant integration source");
        }
    }

    private void ensureSingleEnabledSdv(TenantId tenantId, IntegrationPurpose purpose, boolean enabled, IntegrationConnectionId excludeId) {
        if (!enabled || purpose != IntegrationPurpose.SDV_DELIVERY) return;
        boolean another = repository.findByTenantId(tenantId).stream()
                .anyMatch(c -> c.enabled() && c.purpose() == IntegrationPurpose.SDV_DELIVERY
                        && (excludeId == null || !c.id().equals(excludeId)));
        if (another) throw new ValidationException("Only one SDV delivery connection may be enabled per tenant");
    }

    private String safeAuditDetail(IntegrationConnection connection) {
        return "name=" + connection.name()
                + ",purpose=" + connection.purpose()
                + ",provider=" + connection.providerType()
                + ",enabled=" + connection.enabled()
                + ",configVersion=" + connection.configurationVersion();
    }

    public record ConnectionInput(
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
            boolean preserveSecretReference,
            Integer timeoutSeconds,
            Integer retryMaxAttempts,
            Integer incompleteWindowHours,
            Integer maxDocumentsPerIntake,
            Long maxPdfSizeBytes,
            Integer maxPdfPages,
            IntegrationPollingScheduleType pollingScheduleType,
            Integer pollingIntervalMinutes,
            LocalTime pollingLocalTime,
            String pollingTimeZone,
            Integer pollingBatchLimit,
            IntegrationAcknowledgementMode acknowledgementMode,
            boolean enabled) {

        public ConnectionInput(String name, String description, IntegrationPurpose purpose, IntegrationDirection direction,
                               IntegrationProviderType providerType, IntegrationAuthenticationType authenticationType,
                               String clientPrincipal, String endpoint, String fileShareRoot, String secretReference,
                               boolean preserveSecretReference, Integer timeoutSeconds, Integer retryMaxAttempts,
                               Integer incompleteWindowHours, Integer maxDocumentsPerIntake, Long maxPdfSizeBytes,
                               Integer maxPdfPages, IntegrationAcknowledgementMode acknowledgementMode, boolean enabled) {
            this(name, description, purpose, direction, providerType, authenticationType, clientPrincipal, endpoint, fileShareRoot,
                    secretReference, preserveSecretReference, timeoutSeconds, retryMaxAttempts, incompleteWindowHours,
                    maxDocumentsPerIntake, maxPdfSizeBytes, maxPdfPages, null, null, null, null, null, acknowledgementMode, enabled);
        }
    }
}
