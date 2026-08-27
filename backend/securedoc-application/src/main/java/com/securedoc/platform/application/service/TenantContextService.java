package com.securedoc.platform.application.service;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.domain.tenant.Tenant;

import java.util.Objects;

/**
 * Application service for resolving and validating tenant context.
 * Ensures the tenant exists and is active before use case execution proceeds.
 */
public final class TenantContextService {

    private final TenantRepository tenantRepository;

    public TenantContextService(TenantRepository tenantRepository) {
        this.tenantRepository = Objects.requireNonNull(tenantRepository, "TenantRepository must not be null");
    }

    /**
     * Resolves and validates the tenant for the current principal.
     *
     * @throws NotFoundException if the tenant does not exist
     * @throws IllegalStateException if the tenant is not active
     */
    public Tenant resolveAndValidate(AuthenticatedPrincipal principal) {
        Objects.requireNonNull(principal, "Principal must not be null");

        Tenant tenant = tenantRepository.findById(principal.tenantId())
                .orElseThrow(() -> new NotFoundException("Tenant", principal.tenantId().toString()));

        if (!tenant.isActive()) {
            throw new IllegalStateException(
                    "Tenant is not active: " + tenant.id() + " (status: " + tenant.status() + ")");
        }

        return tenant;
    }
}
