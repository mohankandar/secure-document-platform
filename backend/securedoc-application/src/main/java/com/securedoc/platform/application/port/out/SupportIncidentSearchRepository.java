package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.support.model.SupportIncidentSearchCriteria;
import com.securedoc.platform.application.support.model.SupportIncidentSearchPage;
import com.securedoc.platform.application.support.model.SupportIncidentSummary;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only projection repository for support search. Implementations must enforce tenant scope in every query.
 */
public interface SupportIncidentSearchRepository {
    SupportIncidentSearchPage search(SupportIncidentSearchCriteria criteria);
    Optional<SupportIncidentSummary> findByIncidentId(UUID incidentId, TenantId tenantId);
}
