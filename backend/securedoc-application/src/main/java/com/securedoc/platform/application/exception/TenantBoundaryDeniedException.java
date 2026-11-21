package com.securedoc.platform.application.exception;

import com.securedoc.platform.domain.error.LvErrorCode;

/**
 * An {@link AccessDeniedException} from the tenant (entity) boundary: the request's entity is not one the person may
 * use, or the request names another entity. Recorded with {@code reason=TENANT_BOUNDARY} (task U022 step 0).
 *
 * <p>Task 0025: {@code LV-AUT-003} "No access to this entity" unless the thrower gives another code.
 */
public class TenantBoundaryDeniedException extends AccessDeniedException {

    public TenantBoundaryDeniedException(String message) {
        super(LvErrorCode.AUT_003, message);
    }

    public TenantBoundaryDeniedException(LvErrorCode lvCode, String message) {
        super(lvCode, message);
    }
}
