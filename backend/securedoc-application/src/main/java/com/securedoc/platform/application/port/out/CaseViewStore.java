package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.caseview.CaseViewSettings;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Optional;

/** Task 0108: one person's Cases-page view per entity (table user_case_view, migration 089). */
public interface CaseViewStore {
    Optional<CaseViewSettings> find(TenantId tenantId, ActorId actorId);

    void save(TenantId tenantId, ActorId actorId, CaseViewSettings settings);
}
