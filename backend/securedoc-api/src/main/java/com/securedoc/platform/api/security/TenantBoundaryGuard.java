package com.securedoc.platform.api.security;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.service.TenantContextService;
import com.securedoc.platform.domain.tenant.Tenant;
import org.springframework.stereotype.Component;

@Component
public class TenantBoundaryGuard {
    private final TenantContextService tenantContextService;

    public TenantBoundaryGuard(TenantContextService tenantContextService) {
        this.tenantContextService = tenantContextService;
    }

    public Tenant requireProvisionedTenant(AuthenticatedPrincipal principal) {
        try {
            return tenantContextService.resolveAndValidate(principal);
        } catch (NotFoundException | IllegalStateException ex) {
            throw new AccessDeniedException("Tenant access is not authorized");
        }
    }
}
