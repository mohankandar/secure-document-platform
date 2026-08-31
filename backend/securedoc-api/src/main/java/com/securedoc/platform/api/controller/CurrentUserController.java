package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.dto.CurrentUserResponse;
import com.securedoc.platform.api.security.TenantBoundaryGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class CurrentUserController {
    private final RequestContextResolver contextResolver;
    private final TenantBoundaryGuard tenantBoundaryGuard;

    public CurrentUserController(RequestContextResolver contextResolver, TenantBoundaryGuard tenantBoundaryGuard) {
        this.contextResolver = contextResolver;
        this.tenantBoundaryGuard = tenantBoundaryGuard;
    }

    @GetMapping
    public ResponseEntity<CurrentUserResponse> currentUser(HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var tenant = tenantBoundaryGuard.requireProvisionedTenant(principal);
        return ResponseEntity.ok(new CurrentUserResponse(
                principal.username(), principal.roles(), tenant.name(), tenant.slug(), contextResolver.securityMode().name()));
    }
}
