package com.securedoc.platform.application.support.model;

import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.UUID;

/** Safe, tenant-scoped search criteria for support incident triage. */
public record SupportIncidentSearchCriteria(
        TenantId tenantId,
        String businessIdentifier,
        UUID incidentId,
        UUID correlationId,
        UUID packageId,
        UUID documentId,
        String stage,
        String reasonCode,
        String status,
        String businessOutcome,
        Boolean businessContinuityUsed,
        Boolean retryable,
        Instant from,
        Instant to,
        int page,
        int size) {

    public SupportIncidentSearchCriteria {
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
        page = Math.max(0, page);
        size = Math.max(1, Math.min(size, 100));
        businessIdentifier = trimToNull(businessIdentifier);
        stage = trimToNull(stage);
        reasonCode = trimToNull(reasonCode);
        status = trimToNull(status);
        businessOutcome = trimToNull(businessOutcome);
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must be before or equal to to");
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
