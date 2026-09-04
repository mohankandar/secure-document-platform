package com.securedoc.platform.api.integration;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.integration.IntegrationConnectionService;
import com.securedoc.platform.application.integration.IntegrationConnectionService.ConnectionInput;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.integration.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.time.LocalTime;

@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationConnectionController {
    private final IntegrationConnectionService service;
    private final RequestContextResolver contextResolver;

    public IntegrationConnectionController(IntegrationConnectionService service, RequestContextResolver contextResolver) {
        this.service = service;
        this.contextResolver = contextResolver;
    }

    @GetMapping
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public ResponseEntity<List<IntegrationConnectionResponse>> list(HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        return ResponseEntity.ok(service.list(principal.tenantId()).stream().map(this::toResponse).toList());
    }

    @GetMapping("/{connectionId}")
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public ResponseEntity<IntegrationConnectionResponse> get(@PathVariable String connectionId, HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        return ResponseEntity.ok(toResponse(service.get(principal.tenantId(), parseId(connectionId))));
    }

    @PostMapping
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public ResponseEntity<IntegrationConnectionResponse> create(@RequestBody IntegrationConnectionRequest body,
                                                                 HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var created = service.create(principal.tenantId(), principal.actorId(), contextResolver.resolveCorrelationId(request),
                toInput(body, false));
        return ResponseEntity.ok(toResponse(created));
    }

    @PutMapping("/{connectionId}")
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public ResponseEntity<IntegrationConnectionResponse> update(@PathVariable String connectionId,
                                                                 @RequestBody IntegrationConnectionRequest body,
                                                                 HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var updated = service.update(principal.tenantId(), parseId(connectionId), principal.actorId(),
                contextResolver.resolveCorrelationId(request), toInput(body, true));
        return ResponseEntity.ok(toResponse(updated));
    }

    @PostMapping("/{connectionId}/enable")
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public ResponseEntity<IntegrationConnectionResponse> enable(@PathVariable String connectionId, HttpServletRequest request) {
        return setEnabled(connectionId, true, request);
    }

    @PostMapping("/{connectionId}/disable")
    @RequireCapability(UserCapability.TENANT_INTEGRATION_MANAGE)
    public ResponseEntity<IntegrationConnectionResponse> disable(@PathVariable String connectionId, HttpServletRequest request) {
        return setEnabled(connectionId, false, request);
    }

    private ResponseEntity<IntegrationConnectionResponse> setEnabled(String connectionId, boolean enabled,
                                                                      HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        return ResponseEntity.ok(toResponse(service.setEnabled(principal.tenantId(), parseId(connectionId), enabled,
                principal.actorId(), contextResolver.resolveCorrelationId(request))));
    }

    private ConnectionInput toInput(IntegrationConnectionRequest body, boolean update) {
        if (body == null) throw new ValidationException("Integration connection request is required");
        boolean clearSecret = Boolean.TRUE.equals(body.clearSecretReference());
        boolean preserveSecret = update && body.secretReference() == null && !clearSecret;
        return new ConnectionInput(body.name(), body.description(), parse(body.purpose(), IntegrationPurpose.class, "purpose"),
                parse(body.direction(), IntegrationDirection.class, "direction"),
                parse(body.providerType(), IntegrationProviderType.class, "provider type"),
                parse(body.authenticationType(), IntegrationAuthenticationType.class, "authentication type"),
                body.clientPrincipal(), body.endpoint(), body.fileShareRoot(), clearSecret ? null : body.secretReference(), preserveSecret,
                body.timeoutSeconds(), body.retryMaxAttempts(), body.incompleteWindowHours(), body.maxDocumentsPerIntake(), body.maxPdfSizeBytes(), body.maxPdfPages(),
                parseOptional(body.pollingScheduleType(), IntegrationPollingScheduleType.class), body.pollingIntervalMinutes(), parseLocalTime(body.pollingLocalTime()),
                body.pollingTimeZone(), body.pollingBatchLimit(),
                parse(body.acknowledgementMode(), IntegrationAcknowledgementMode.class, "acknowledgement mode"),
                Boolean.TRUE.equals(body.enabled()));
    }

    private <E extends Enum<E>> E parse(String raw, Class<E> type, String label) {
        if (raw == null || raw.isBlank()) throw new ValidationException("Integration " + label + " is required");
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unsupported integration " + label);
        }
    }


    private <E extends Enum<E>> E parseOptional(String raw, Class<E> type) {
        if (raw == null || raw.isBlank()) return null;
        try { return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new ValidationException("Unsupported polling schedule type"); }
    }

    private LocalTime parseLocalTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return LocalTime.parse(raw.trim()); }
        catch (RuntimeException e) { throw new ValidationException("Polling local time must use HH:mm format"); }
    }

    private IntegrationConnectionId parseId(String raw) {
        try {
            return IntegrationConnectionId.fromString(raw);
        } catch (RuntimeException e) {
            throw new ValidationException("Invalid integration connection id");
        }
    }

    private IntegrationConnectionResponse toResponse(IntegrationConnection connection) {
        return new IntegrationConnectionResponse(connection.id().value(), connection.name(), connection.description(),
                connection.purpose().name(), connection.direction().name(), connection.providerType().name(),
                connection.authenticationType().name(), connection.clientPrincipal(), connection.endpoint(), connection.fileShareRoot(),
                connection.secretConfigured(), connection.timeoutSeconds(), connection.retryMaxAttempts(), connection.incompleteWindowHours(),
                connection.maxDocumentsPerIntake(), connection.maxPdfSizeBytes(), connection.maxPdfPages(),
                connection.pollingScheduleType() == null ? null : connection.pollingScheduleType().name(), connection.pollingIntervalMinutes(),
                connection.pollingLocalTime() == null ? null : connection.pollingLocalTime().toString(), connection.pollingTimeZone(), connection.pollingBatchLimit(),
                connection.acknowledgementMode().name(), connection.enabled(), connection.configurationVersion(),
                connection.healthStatus().name(), connection.lastSuccessAt(), connection.lastFailureAt(),
                connection.lastFailureCategory(), connection.createdAt(), connection.updatedAt());
    }
}
