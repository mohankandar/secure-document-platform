package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.dto.CurrentUserResponse;
import com.securedoc.platform.api.security.TenantBoundaryGuard;
import com.securedoc.platform.api.security.ProductAuthorizationPolicy;
import com.securedoc.platform.application.port.out.EntityTimeZonePort;
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
    private final ProductAuthorizationPolicy authorizationPolicy;
    private final EntityTimeZonePort zones;

    public CurrentUserController(RequestContextResolver contextResolver,
                                 TenantBoundaryGuard tenantBoundaryGuard,
                                 ProductAuthorizationPolicy authorizationPolicy, EntityTimeZonePort zones) {
        this.zones = zones;
        this.contextResolver = contextResolver;
        this.tenantBoundaryGuard = tenantBoundaryGuard;
        this.authorizationPolicy = authorizationPolicy;
    }

    @com.securedoc.platform.api.security.OpenEndpoint("the signed-in person's own identity")
    @GetMapping
    public ResponseEntity<CurrentUserResponse> currentUser(HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var tenant = tenantBoundaryGuard.requireProvisionedTenant(principal);
        return ResponseEntity.ok(new CurrentUserResponse(
                principal.username(), principal.roles(), tenant.name(), tenant.slug(), contextResolver.securityMode().name(),
                authorizationPolicy.capabilityNamesFor(principal.roles()),
                zones.effective(principal.tenantId()).map(java.time.ZoneId::getId).orElse(null)));
    }
}
