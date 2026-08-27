package com.securedoc.platform.application.exception;

import com.securedoc.platform.domain.tenant.Capability;

/**
 * Thrown when a tenant attempts to use a capability that is not enabled in their profile.
 */
public class CapabilityNotEnabledException extends ApplicationException {

    private final Capability capability;

    public CapabilityNotEnabledException(Capability capability) {
        super("CAPABILITY_DISABLED", "Capability not enabled for tenant: " + capability.displayName());
        this.capability = capability;
    }

    public Capability capability() {
        return capability;
    }
}
