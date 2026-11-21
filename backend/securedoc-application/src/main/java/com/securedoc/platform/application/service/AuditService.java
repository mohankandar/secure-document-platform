package com.securedoc.platform.application.service;

import com.securedoc.platform.application.audit.AuditCaseIds;
import com.securedoc.platform.application.audit.RequestActorDisplayName;
import com.securedoc.platform.application.port.out.AuditPort;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditOutcome;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Objects;

/**
 * Application service for recording audit events through the AuditPort.
 * Ensures audit metadata does not contain raw sensitive values.
 */
public final class AuditService {

    private final AuditPort auditPort;
    private final RequestActorDisplayName actorDisplayName;

    /** No display names are recorded (tests and callers outside a request). */
    public AuditService(AuditPort auditPort) {
        this(auditPort, new RequestActorDisplayName());
    }

    /**
     * Records the current request's display name when the record's actor is the person the request is for
     * (task 0016). The source is filled by the API layer; system work never has a name.
     */
    public AuditService(AuditPort auditPort, RequestActorDisplayName actorDisplayName) {
        this.auditPort = Objects.requireNonNull(auditPort, "AuditPort must not be null");
        this.actorDisplayName = Objects.requireNonNull(actorDisplayName, "RequestActorDisplayName must not be null");
    }

    /**
     * Runs {@code work} with a machine client's name bound for {@code actorId} on this thread (task 0018 A6), so every
     * record that actor writes during the work carries {@code "<kind>: <configured name>"}. The binding is always
     * cleared afterwards, also on failure, so a later record on the same thread gets no name. Only for threads that
     * carry no request (schedulers): clearing also drops any binding made before.
     */
    public <T> T withMachineClientName(ActorId actorId, RequestActorDisplayName.MachineClientKind kind,
                                       String configuredName, java.util.function.Supplier<T> work) {
        actorDisplayName.bindMachineClient(actorId, kind, configuredName);
        try {
            return work.get();
        } finally {
            actorDisplayName.clear();
        }
    }

    /**
     * Records a successful action.
     */
    public void recordSuccess(TenantId tenantId, ActorId actorId, AuditAction action,
                               String resourceType, String resourceId,
                               CorrelationId correlationId, String detail) {
        record(tenantId, actorId, action, resourceType, resourceId,
                AuditOutcome.SUCCESS, correlationId, detail);
    }

    /**
     * Records a failed action.
     */
    public void recordFailure(TenantId tenantId, ActorId actorId, AuditAction action,
                               String resourceType, String resourceId,
                               CorrelationId correlationId, String detail) {
        record(tenantId, actorId, action, resourceType, resourceId,
                AuditOutcome.FAILURE, correlationId, detail);
    }

    /**
     * Records a denied action.
     */
    public void recordDenied(TenantId tenantId, ActorId actorId, AuditAction action,
                              String resourceType, String resourceId,
                              CorrelationId correlationId, String detail) {
        record(tenantId, actorId, action, resourceType, resourceId,
                AuditOutcome.DENIED, correlationId, detail);
    }

    /**
     * Records a successful action about something inside a case (a document, finding or artifact), with the case
     * (package) ID the caller already holds (task 0017). A null {@code caseId} falls back to the derived one.
     */
    public void recordSuccess(TenantId tenantId, ActorId actorId, AuditAction action,
                              String resourceType, String resourceId, PackageId caseId,
                              CorrelationId correlationId, String detail) {
        record(tenantId, actorId, action, resourceType, resourceId, caseId,
                AuditOutcome.SUCCESS, correlationId, detail);
    }

    /** As {@link #recordSuccess(TenantId, ActorId, AuditAction, String, String, PackageId, CorrelationId, String)}. */
    public void recordFailure(TenantId tenantId, ActorId actorId, AuditAction action,
                              String resourceType, String resourceId, PackageId caseId,
                              CorrelationId correlationId, String detail) {
        record(tenantId, actorId, action, resourceType, resourceId, caseId,
                AuditOutcome.FAILURE, correlationId, detail);
    }

    /** As {@link #recordSuccess(TenantId, ActorId, AuditAction, String, String, PackageId, CorrelationId, String)}. */
    public void recordDenied(TenantId tenantId, ActorId actorId, AuditAction action,
                             String resourceType, String resourceId, PackageId caseId,
                             CorrelationId correlationId, String detail) {
        record(tenantId, actorId, action, resourceType, resourceId, caseId,
                AuditOutcome.DENIED, correlationId, detail);
    }

    /** What an unknown sensitivity level was sent for (task 0021). */
    public enum SensitivityScope { CASE, DOCUMENT }

    /**
     * Records the LV-INT-005 warning: a source sent an unknown sensitivity level, taken as Standard (task 0021).
     * The intake is not refused. The detail holds the code and scope only, never the value sent, so Reports can count it.
     */
    public void recordUnknownSensitivityLevel(TenantId tenantId, ActorId actorId, PackageId caseId,
                                              SensitivityScope scope, CorrelationId correlationId) {
        record(tenantId, actorId, AuditAction.INTAKE_SENSITIVITY_LEVEL_UNKNOWN, "IntakeBatch",
                caseId == null ? null : caseId.toString(), caseId, AuditOutcome.SUCCESS, correlationId,
                "code=" + com.securedoc.platform.domain.document.SensitivityLevel.UNKNOWN_LEVEL_WARNING
                        + ",scope=" + scope.name());
    }

    private void record(TenantId tenantId, ActorId actorId, AuditAction action,
                         String resourceType, String resourceId,
                         AuditOutcome outcome, CorrelationId correlationId, String detail) {
        record(tenantId, actorId, action, resourceType, resourceId, null, outcome, correlationId, detail);
    }

    private void record(TenantId tenantId, ActorId actorId, AuditAction action,
                         String resourceType, String resourceId, PackageId caseId,
                         AuditOutcome outcome, CorrelationId correlationId, String detail) {
        String safeDetail = sanitizeDetail(detail);
        String recordedCaseId = caseId != null
                ? caseId.value().toString()
                : AuditCaseIds.caseIdFor(resourceType, resourceId, safeDetail);
        AuditRecord auditRecord = AuditRecord.create(
                tenantId, actorId, action, resourceType, resourceId,
                outcome, correlationId, safeDetail,
                actorDisplayName.displayNameFor(actorId).orElse(null),
                recordedCaseId);
        auditPort.record(auditRecord);
    }

    /**
     * Strips potentially sensitive values from audit detail.
     * Only structural/metadata information should be logged.
     */
    private String sanitizeDetail(String detail) {
        if (detail == null) {
            return null;
        }
        // Do not log raw file contents, passwords, tokens, etc.
        // The detail should already be safe metadata by convention,
        // but truncate excessively long details as a safety measure.
        if (detail.length() > 1000) {
            return detail.substring(0, 1000) + "...[truncated]";
        }
        return detail;
    }
}
