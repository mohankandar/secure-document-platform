package com.securedoc.platform.application.exception;

/**
 * An {@link AccessDeniedException} from the tenant (entity) boundary: the request's entity is not one the person may
 * use, or the request names another entity. Recorded with {@code reason=TENANT_BOUNDARY} (task U022 step 0).
 */
public class TenantBoundaryDeniedException extends AccessDeniedException {

    public TenantBoundaryDeniedException(String message) {
        super(message);
    }
}
