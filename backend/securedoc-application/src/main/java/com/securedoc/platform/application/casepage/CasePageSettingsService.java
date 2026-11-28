package com.securedoc.platform.application.casepage;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.CasePageSettingsStore;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Duration;
import java.util.Objects;

/**
 * Task 0108 (addendum 2) and 0111 question 1: reads and saves the entity's Cases-page settings. An entity without a row
 * has the defaults (4 h amber, 8 h red, the installation's empty-case expiry). A save outside the allowed ranges is
 * refused whole (LV-CFG-001); a save that changes nothing writes nothing and no audit record.
 */
public final class CasePageSettingsService {
    private final CasePageSettingsStore store;
    private final AuditService audit;
    private final int defaultExpiryDays;

    public CasePageSettingsService(CasePageSettingsStore store, AuditService audit, int defaultExpiryDays) {
        this.store = Objects.requireNonNull(store);
        this.audit = Objects.requireNonNull(audit);
        if (defaultExpiryDays < 1 || defaultExpiryDays > CasePageSettings.MAX_EXPIRY_DAYS) {
            throw new IllegalArgumentException("securedoc.cases.empty-case-expiry-days must be from 1 to 365");
        }
        this.defaultExpiryDays = defaultExpiryDays;
    }

    public CasePageSettings read(TenantId tenantId) {
        return store.find(tenantId).orElseGet(() -> CasePageSettings.defaults(defaultExpiryDays));
    }

    public CasePageSettings save(TenantId tenantId, ActorId actorId, CorrelationId correlationId,
                                 Integer waitAmberMinutes, Integer waitRedMinutes, Integer emptyCaseExpiryDays) {
        if (waitAmberMinutes == null || waitRedMinutes == null || emptyCaseExpiryDays == null) {
            throw new ValidationException(LvErrorCode.CFG_001, "Amber, red and the expiry days are all required");
        }
        CasePageSettings requested;
        try {
            requested = new CasePageSettings(waitAmberMinutes, waitRedMinutes, emptyCaseExpiryDays);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(LvErrorCode.CFG_001, e.getMessage());
        }
        CasePageSettings before = read(tenantId);
        if (before.equals(requested) && store.find(tenantId).isPresent()) return before;
        store.save(tenantId, actorId, requested);
        audit.recordSuccess(tenantId, actorId, AuditAction.DASHBOARD_CONFIGURATION_CHANGED, "CasePageSettings",
                tenantId.toString(), correlationId,
                "Cases page settings changed: waitAmberMinutes=" + requested.waitAmberMinutes()
                        + ", waitRedMinutes=" + requested.waitRedMinutes()
                        + ", emptyCaseExpiryDays=" + requested.emptyCaseExpiryDays()
                        + "; previous=[waitAmberMinutes=" + before.waitAmberMinutes()
                        + ", waitRedMinutes=" + before.waitRedMinutes()
                        + ", emptyCaseExpiryDays=" + before.emptyCaseExpiryDays() + "]");
        return requested;
    }

    /** How long a case of this entity may stay without documents. */
    public Duration emptyCaseExpiry(TenantId tenantId) {
        return Duration.ofDays(read(tenantId).emptyCaseExpiryDays());
    }

    /** The shortest expiry of any entity (the installation default included): the candidate cut-off. */
    public Duration shortestEmptyCaseExpiry() {
        int days = Math.min(defaultExpiryDays, store.shortestExpiryDays().orElse(defaultExpiryDays));
        return Duration.ofDays(days);
    }

    public int defaultExpiryDays() { return defaultExpiryDays; }
}
