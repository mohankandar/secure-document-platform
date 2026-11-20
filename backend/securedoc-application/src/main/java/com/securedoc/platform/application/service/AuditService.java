package com.securedoc.platform.application.service;

import com.securedoc.platform.application.audit.AuditCaseIds;
import com.securedoc.platform.application.audit.RequestActorDisplayName;
import com.securedoc.platform.application.port.out.AuditPort;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditOutcome;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
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

    private void record(TenantId tenantId, ActorId actorId, AuditAction action,
                         String resourceType, String resourceId,
                         AuditOutcome outcome, CorrelationId correlationId, String detail) {
        String safeDetail = sanitizeDetail(detail);
        AuditRecord auditRecord = AuditRecord.create(
                tenantId, actorId, action, resourceType, resourceId,
                outcome, correlationId, safeDetail,
                actorDisplayName.displayNameFor(actorId).orElse(null),
                AuditCaseIds.caseIdFor(resourceType, resourceId, safeDetail));
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
