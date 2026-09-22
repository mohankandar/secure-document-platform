package com.securedoc.platform.application.support.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.lifecycle.command.ExtendTechnicalHoldCommand;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.technical.service.RetryTechnicalFailureService;
import com.securedoc.platform.application.port.out.ProcessingIncidentRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.technical.command.RetryTechnicalFailureCommand;
import com.securedoc.platform.application.technical.service.ProcessingIncidentService;
import com.securedoc.platform.application.telemetry.ProductTelemetryService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Exceptional Tenant-IT actions for durable processing incidents. Normal business flow remains automatic. */
public final class SupportIncidentActionService {
    private final ProcessingIncidentRepository incidentRepository;
    private final PackageLifecycleService lifecycleService;
    private final ProcessingIncidentService incidentService;
    private final RetryTechnicalFailureService retryUseCase;
    private final AuditService auditService;
    private final ProductTelemetryService productTelemetryService;

    public SupportIncidentActionService(ProcessingIncidentRepository incidentRepository,
                                        PackageLifecycleService lifecycleService,
                                        ProcessingIncidentService incidentService,
                                        RetryTechnicalFailureService retryUseCase,
                                        AuditService auditService) {
        this(incidentRepository, lifecycleService, incidentService, retryUseCase, auditService,
                ProductTelemetryService.noop());
    }

    public SupportIncidentActionService(ProcessingIncidentRepository incidentRepository,
                                        PackageLifecycleService lifecycleService,
                                        ProcessingIncidentService incidentService,
                                        RetryTechnicalFailureService retryUseCase,
                                        AuditService auditService,
                                        ProductTelemetryService productTelemetryService) {
        this.incidentRepository = Objects.requireNonNull(incidentRepository);
        this.lifecycleService = Objects.requireNonNull(lifecycleService);
        this.incidentService = Objects.requireNonNull(incidentService);
        this.retryUseCase = Objects.requireNonNull(retryUseCase);
        this.auditService = Objects.requireNonNull(auditService);
        this.productTelemetryService = Objects.requireNonNull(productTelemetryService);
    }

    public ActionResult execute(UUID incidentId,
                                TenantId tenantId,
                                ActorId actorId,
                                CorrelationId correlationId,
                                String action,
                                Integer holdDays,
                                String resolutionCode) {
        var incident = incidentRepository.findByIdAndTenantId(incidentId, tenantId)
                .orElseThrow(() -> new NotFoundException("ProcessingIncident", incidentId.toString()));
        String normalized = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if (incident.status() == com.securedoc.platform.domain.processing.ProcessingIncidentStatus.RESOLVED
                || incident.status() == com.securedoc.platform.domain.processing.ProcessingIncidentStatus.AUTO_CLOSED) {
            throw new ValidationException("This support incident is historical/closed; active package actions are unavailable");
        }

        switch (normalized) {
            case "REPROCESS" -> retryUseCase.executeSupportOverride(new RetryTechnicalFailureCommand(
                    incident.packageId(), tenantId, actorId, correlationId));
            case "PRESERVE_FOR_INVESTIGATION" -> {
                int days = holdDays == null ? 7 : holdDays;
                if (days != 7 && days != 14 && days != 30) {
                    throw new ValidationException("Investigation hold must be 7, 14, or 30 days");
                }
                int hours = days * 24;
                lifecycleService.execute(new ExtendTechnicalHoldCommand(
                        incident.packageId(), tenantId, actorId, correlationId, hours));
                incidentService.markInvestigationHold(incident.packageId(), tenantId, correlationId, hours);
            }
            case "RELEASE_HOLD" -> {
                lifecycleService.restartTechnicalAttentionRetention(
                        incident.packageId(), tenantId, actorId, correlationId);
                incidentService.markInvestigationHoldReleased(incident.packageId(), tenantId, correlationId);
            }
            case "RESOLVE_NO_REPROCESS" -> {
                lifecycleService.restartTechnicalAttentionRetention(
                        incident.packageId(), tenantId, actorId, correlationId);
                incidentService.resolveNoReprocess(incident.packageId(), tenantId, correlationId,
                        normalizeResolution(resolutionCode, "RESOLVED_NO_REPROCESS"), false);
            }
            case "SUPERSEDED", "DUPLICATE" -> {
                lifecycleService.restartTechnicalAttentionRetention(
                        incident.packageId(), tenantId, actorId, correlationId);
                incidentService.resolveNoReprocess(incident.packageId(), tenantId, correlationId,
                        normalizeResolution(resolutionCode, normalized), true);
            }
            default -> throw new ValidationException("Unsupported support incident action: " + action);
        }

        auditService.recordSuccess(tenantId, actorId, AuditAction.SUPPORT_INCIDENT_ACTION_RECORDED,
                "ProcessingIncident", incidentId.toString(), correlationId,
                "Support incident action recorded: action=" + normalized);
        try {
            productTelemetryService.technicalAttentionAction(tenantId, incident.packageId(), normalized,
                    "PRESERVE_FOR_INVESTIGATION".equals(normalized) ? (holdDays == null ? 7 : holdDays) : null);
        } catch (RuntimeException ignored) {
            // Product telemetry is non-authoritative and must never affect support actions.
        }
        return new ActionResult(incidentId, incident.packageId().value(), normalized, "ACCEPTED");
    }

    private String normalizeResolution(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
        if (normalized.length() > 64) normalized = normalized.substring(0, 64);
        return normalized.isBlank() ? fallback : normalized;
    }

    public record ActionResult(UUID incidentId, UUID packageId, String action, String status) {}
}
