package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.casepage.CasePageSettings;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Optional;
import java.util.OptionalInt;

/** Task 0108: the entity's Cases-page settings (table case_page_settings, migration 092). */
public interface CasePageSettingsStore {
    Optional<CasePageSettings> find(TenantId tenantId);

    void save(TenantId tenantId, ActorId actorId, CasePageSettings settings);

    /** The shortest expiry any entity has set, or empty when no entity has a row. */
    OptionalInt shortestExpiryDays();
}
