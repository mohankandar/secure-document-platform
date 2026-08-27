package com.securedoc.platform.domain.tenant;

/**
 * Lifecycle status of a tenant.
 */
public enum TenantStatus {

    /** Tenant provisioned but not yet active. */
    PROVISIONING,

    /** Tenant is active and operational. */
    ACTIVE,

    /** Tenant is suspended (access restricted). */
    SUSPENDED,

    /** Tenant is deactivated (data retained, no access). */
    DEACTIVATED
}
