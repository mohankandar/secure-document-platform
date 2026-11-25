package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.TenantId;

/**
 * Task 0061: one entity's case fields at a time. A case being made holds {@link #using} until it is stored, so its
 * business record is not erased and its fields are not changed meanwhile; {@link #changing} waits for those. Both last
 * until the caller's transaction ends.
 */
public interface CaseFieldLock {
    CaseFieldLock NONE = new CaseFieldLock() {
        @Override
        public void using(TenantId tenantId) {
        }

        @Override
        public void changing(TenantId tenantId) {
        }
    };

    void using(TenantId tenantId);

    void changing(TenantId tenantId);
}
