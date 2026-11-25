package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.TenantId;

/**
 * Task 0081: counts what intake refused and what it had already taken in (migration 072). Best effort: it never throws, and
 * is saved although the refused request is not. {@code sourceKey} as in case_priorities, or {@link #MANUAL}.
 */
public interface IntakeRefusalPort {
    String MANUAL = "type:MANUAL_UPLOAD";

    void record(TenantId tenantId, String sourceKey, LvErrorCode code);

    IntakeRefusalPort NONE = (tenantId, sourceKey, code) -> { };
}
