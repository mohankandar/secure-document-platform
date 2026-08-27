package com.securedoc.platform.domain.tenant;

/**
 * Activation status of a capability for a tenant.
 */
public enum CapabilityStatus {

    /** Capability is enabled and available. */
    ENABLED,

    /** Capability is disabled (not available). */
    DISABLED,

    /** Capability is in trial/evaluation period. */
    TRIAL,

    /** Capability is provisioned but not yet configured. */
    PENDING_CONFIGURATION
}
