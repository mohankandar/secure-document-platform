package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.audit.query.AuditRecordFilter;
import com.securedoc.platform.application.audit.query.AuditRecordSummary;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.List;
import java.util.Set;

/**
 * Outbound port for querying persisted audit records.
 * All queries must be tenant-scoped.
 */
public interface AuditQueryPort {
    List<AuditRecordSummary> findByFilter(AuditRecordFilter filter);

    /** Task 0119: what a person did to a finding in Review: confirmed, dismissed, moved its box, checked its page. */
    Set<String> PERSON_DECISIONS = Set.of("FINDING_CONFIRMED", "FINDING_DISMISSED", "FINDING_BOUNDING_BOX_UPDATED",
            "REVIEW_PAGE_CHECKED");

    /**
     * Task 0119: the ids of the findings of this case a person decided (never the system actor). This default knows none,
     * so no page counts as finished by a person (every page is read again: the safe side).
     */
    default Set<String> findingsDecidedByAPerson(TenantId tenantId, String caseId) {
        return Set.of();
    }
}
