package com.securedoc.platform.application.integration;

import com.securedoc.platform.domain.error.LvErrorCode;
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
    private com.securedoc.platform.application.port.out.EntityTimeZonePort zones = com.securedoc.platform.application.port.out.EntityTimeZonePort.NONE;

    public IntegrationConnectionService(IntegrationConnectionRepository repository, AuditService auditService) {
        this(repository, auditService, Clock.systemUTC());
    }

    IntegrationConnectionService(IntegrationConnectionRepository repository, AuditService auditService, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.auditService = Objects.requireNonNull(auditService);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Task 0058: the entity's time zone, which a daily schedule without one of its own follows. */
    public IntegrationConnectionService withEntityTimeZones(com.securedoc.platform.application.port.out.EntityTimeZonePort zones) {
        this.zones = Objects.requireNonNull(zones);
        return this;
    }

    /** Task 0087: the destinations' profiles, which a document store connection's settings are checked against. */
    public IntegrationConnectionService withDestinationProfiles(com.securedoc.platform.application.port.out.DestinationProfilePort profiles) {
        this.destinationProfiles = Objects.requireNonNull(profiles);
        return this;
    }

    private com.securedoc.platform.application.port.out.DestinationProfilePort destinationProfiles;

    /** A daily schedule without a time zone needs an entity that has one; otherwise it would never run. */
    private void requireZone(TenantId tenantId, ConnectionInput input) {
        boolean none = input.pollingTimeZone() == null || input.pollingTimeZone().isBlank();
        if (none && input.pollingScheduleType() == com.securedoc.platform.domain.integration.IntegrationPollingScheduleType.DAILY
                && zones.effective(tenantId).isEmpty()) {
            throw new ValidationException(LvErrorCode.CFG_001, "Daily polling requires a time zone");
        }
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
        IntegrationConnection connection = prepareCreate(tenantId, input);
        repository.save(connection);
        auditService.recordSuccess(tenantId, actorId, AuditAction.INTEGRATION_CONNECTION_CREATED,
                "IntegrationConnection", connection.id().toString(), correlationId,
                safeAuditDetail(connection));
        return connection;
    }

    public IntegrationConnection update(TenantId tenantId, IntegrationConnectionId id,
                                        ActorId actorId, CorrelationId correlationId, ConnectionInput input) {
        IntegrationConnection updated = prepareUpdate(tenantId, id, input);
        repository.save(updated);
        auditService.recordSuccess(tenantId, actorId, AuditAction.INTEGRATION_CONNECTION_UPDATED,
                "IntegrationConnection", updated.id().toString(), correlationId,
                safeAuditDetail(updated));
        return updated;
    }

    /**
     * Builds the connection that Save would store, through exactly the same checks as Save, but stores and audits
     * nothing. With an id, the connection must belong to this tenant (the person's active entity), or
     * {@link NotFoundException} is thrown, so another entity's stored key is never reached.
     */
    public IntegrationConnection draft(TenantId tenantId, IntegrationConnectionId idOrNull, ConnectionInput input) {
        return idOrNull == null ? prepareCreate(tenantId, input) : prepareUpdate(tenantId, idOrNull, input);
    }

    private IntegrationConnection prepareCreate(TenantId tenantId, ConnectionInput input) {
        Objects.requireNonNull(input, "Integration connection input is required");
        ensureUniqueName(tenantId, input.name(), null);
        ensureUniqueClientPrincipal(tenantId, input, null);
        ensureSingleEnabledDocumentStore(tenantId, input.purpose(), input.enabled(), null);
        requireZone(tenantId, input);
        try {
            return checkedDestination(IntegrationConnection.create(tenantId, input.name(), input.description(), input.purpose(),
                    input.direction(), input.providerType(), input.authenticationType(), input.clientPrincipal(), input.endpoint(),
                    input.fileShareRoot(), input.secretReference(), input.timeoutSeconds(), input.retryMaxAttempts(), input.incompleteWindowHours(), input.maxDocumentsPerIntake(), input.maxPdfSizeBytes(), input.maxPdfPages(),
                    input.pollingScheduleType(), input.pollingIntervalMinutes(), input.pollingLocalTime(), input.pollingTimeZone(), input.pollingBatchLimit(),
                    input.acknowledgementMode(), input.enabled(), Instant.now(clock)), input.destination());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(LvErrorCode.CFG_001, e.getMessage());
        }
    }

    private IntegrationConnection prepareUpdate(TenantId tenantId, IntegrationConnectionId id, ConnectionInput input) {
        Objects.requireNonNull(input, "Integration connection input is required");
        IntegrationConnection existing = get(tenantId, id);
        ensureUniqueName(tenantId, input.name(), id);
        ensureUniqueClientPrincipal(tenantId, input, id);
        ensureSingleEnabledDocumentStore(tenantId, input.purpose(), existing.enabled(), id);
        requireZone(tenantId, input);
        try {
            // A caller that knows nothing of destinations (the old screen) keeps what is stored.
            return checkedDestination(existing.withDestination(null).updateConfiguration(input.name(), input.description(), input.purpose(), input.direction(),
                    input.providerType(), input.authenticationType(), input.clientPrincipal(), input.endpoint(), input.fileShareRoot(),
                    input.secretReference(), input.preserveSecretReference(), input.timeoutSeconds(), input.retryMaxAttempts(), input.incompleteWindowHours(), input.maxDocumentsPerIntake(), input.maxPdfSizeBytes(), input.maxPdfPages(),
                    input.pollingScheduleType(), input.pollingIntervalMinutes(), input.pollingLocalTime(), input.pollingTimeZone(), input.pollingBatchLimit(),
                    input.acknowledgementMode(), Instant.now(clock)),
                    input.destination() != null || input.purpose() != IntegrationPurpose.DOCUMENT_STORE_DELIVERY
                            ? input.destination() : existing.destination());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(LvErrorCode.CFG_001, e.getMessage());
        }
    }

    public IntegrationConnection setEnabled(TenantId tenantId, IntegrationConnectionId id, boolean enabled,
                                            ActorId actorId, CorrelationId correlationId) {
        IntegrationConnection existing = get(tenantId, id);
        if (existing.enabled() == enabled) return existing;
        ensureSingleEnabledDocumentStore(tenantId, existing.purpose(), enabled, id);
        IntegrationConnection updated;
        try {
            updated = existing.setEnabled(enabled, Instant.now(clock));
            if (enabled) updated = checkedDestination(updated, updated.destination());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(LvErrorCode.CFG_001, e.getMessage());
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
            throw new ValidationException(LvErrorCode.CFG_003, "Integration connection name already exists for this tenant");
        }
    }

    public IntegrationConnection resolveEnabledPushSource(TenantId tenantId, String clientPrincipal) {
        if (clientPrincipal == null || clientPrincipal.isBlank()) {
            throw new ValidationException(LvErrorCode.AUT_008, "Authenticated integration client principal is required");
        }
        return repository.findEnabledPushSourceByTenantIdAndClientPrincipal(tenantId, clientPrincipal.trim())
                .orElseThrow(() -> new com.securedoc.platform.application.exception.AccessDeniedException(LvErrorCode.AUT_008,
                        "Authenticated client is not bound to an enabled push-ingestion source"));
    }

    public IntegrationConnection resolveEnabledScannerSource(TenantId tenantId, String clientPrincipal) {
        if (clientPrincipal == null || clientPrincipal.isBlank()) {
            throw new ValidationException(LvErrorCode.AUT_008, "Authenticated scanner client principal is required");
        }
        return repository.findEnabledScannerSourceByTenantIdAndClientPrincipal(tenantId, clientPrincipal.trim())
                .orElseThrow(() -> new com.securedoc.platform.application.exception.AccessDeniedException(LvErrorCode.AUT_008,
                        "Authenticated client is not bound to an enabled scanner-ingestion source"));
    }

    private void ensureUniqueClientPrincipal(TenantId tenantId, ConnectionInput input, IntegrationConnectionId excludeId) {
        if ((input.providerType() != IntegrationProviderType.PUSH_API && input.providerType() != IntegrationProviderType.SCANNER)
                || input.clientPrincipal() == null || input.clientPrincipal().isBlank()) return;
        if (repository.existsByTenantIdAndClientPrincipal(tenantId, input.clientPrincipal().trim(), excludeId)) {
            throw new ValidationException(LvErrorCode.CFG_003, "Authenticated client principal is already bound to another tenant integration source");
        }
    }

    /**
     * Task 0087: the destination settings as they are stored. They are checked against the profile file; a connection
     * that is switched on must have them. One that is switched off may wait for its profile. Without the port (no
     * delivery to a destination in this process) nothing can be checked, so nothing is changed.
     */
    private IntegrationConnection checkedDestination(IntegrationConnection connection,
                                                     com.securedoc.platform.domain.integration.DestinationSettings settings) {
        IntegrationConnection wanted = connection.withDestination(settings);
        if (destinationProfiles == null || wanted.purpose() != IntegrationPurpose.DOCUMENT_STORE_DELIVERY) return wanted;
        if (settings == null) {
            if (!wanted.enabled()) return wanted;
            throw new ValidationException(LvErrorCode.CFG_003, "Choose the destination's profile before switching the connection on");
        }
        return destinationProfiles.checked(wanted);
    }

    private void ensureSingleEnabledDocumentStore(TenantId tenantId, IntegrationPurpose purpose, boolean enabled, IntegrationConnectionId excludeId) {
        if (!enabled || purpose != IntegrationPurpose.DOCUMENT_STORE_DELIVERY) return;
        boolean another = repository.findByTenantId(tenantId).stream()
                .anyMatch(c -> c.enabled() && c.purpose() == IntegrationPurpose.DOCUMENT_STORE_DELIVERY
                        && (excludeId == null || !c.id().equals(excludeId)));
        if (another) throw new ValidationException(LvErrorCode.CFG_005, "Only one document store delivery connection may be enabled per tenant");
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
            boolean enabled,
            /* Task 0087: null for every connection but a document store's. */
            com.securedoc.platform.domain.integration.DestinationSettings destination) {

        public ConnectionInput(String name, String description, IntegrationPurpose purpose, IntegrationDirection direction,
                               IntegrationProviderType providerType, IntegrationAuthenticationType authenticationType,
                               String clientPrincipal, String endpoint, String fileShareRoot, String secretReference,
                               boolean preserveSecretReference, Integer timeoutSeconds, Integer retryMaxAttempts,
                               Integer incompleteWindowHours, Integer maxDocumentsPerIntake, Long maxPdfSizeBytes,
                               Integer maxPdfPages, IntegrationPollingScheduleType pollingScheduleType,
                               Integer pollingIntervalMinutes, LocalTime pollingLocalTime, String pollingTimeZone,
                               Integer pollingBatchLimit, IntegrationAcknowledgementMode acknowledgementMode, boolean enabled) {
            this(name, description, purpose, direction, providerType, authenticationType, clientPrincipal, endpoint, fileShareRoot,
                    secretReference, preserveSecretReference, timeoutSeconds, retryMaxAttempts, incompleteWindowHours,
                    maxDocumentsPerIntake, maxPdfSizeBytes, maxPdfPages, pollingScheduleType, pollingIntervalMinutes, pollingLocalTime,
                    pollingTimeZone, pollingBatchLimit, acknowledgementMode, enabled, null);
        }

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
