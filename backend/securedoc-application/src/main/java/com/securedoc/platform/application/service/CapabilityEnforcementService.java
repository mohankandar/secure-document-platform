package com.securedoc.platform.application.service;

import com.securedoc.platform.application.exception.CapabilityNotEnabledException;
import com.securedoc.platform.domain.tenant.Capability;
import com.securedoc.platform.domain.tenant.Tenant;

import java.util.Objects;

/**
 * Application service for enforcing tenant capability requirements.
 * Throws if the tenant does not have a required capability enabled.
 */
public final class CapabilityEnforcementService {

    /**
     * Ensures the tenant has the specified capability enabled.
     *
     * @throws CapabilityNotEnabledException if the capability is disabled for this tenant
     */
    public void require(Tenant tenant, Capability capability) {
        Objects.requireNonNull(tenant, "Tenant must not be null");
        Objects.requireNonNull(capability, "Capability must not be null");

        if (!tenant.isCapabilityEnabled(capability)) {
            throw new CapabilityNotEnabledException(capability);
        }
    }
}
