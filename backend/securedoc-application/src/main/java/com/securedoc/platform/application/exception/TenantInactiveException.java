package com.securedoc.platform.application.exception;

/**
 * The principal's tenant exists but is not active (task 0023 C2). Still an {@link IllegalStateException}, so the
 * API's existing mapping is unchanged; the type lets intake record {@code TENANT_INACTIVE} instead of a generic code.
 */
public class TenantInactiveException extends IllegalStateException {
    public TenantInactiveException(String message) {
        super(message);
    }
}
