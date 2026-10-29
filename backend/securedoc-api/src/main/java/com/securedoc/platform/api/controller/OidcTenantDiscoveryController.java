package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.domain.tenant.TenantStatus;
import com.securedoc.platform.api.security.SecurityMode;
import com.securedoc.platform.api.context.SecurityContextException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Bootstrap endpoint for shared-issuer users before selecting a tenant.
 * SecurityConfig still requires valid OIDC JWT. RequestContextResolver performs
 * ACTIVE connection and tenant-scoped group authorization.
 */
@RestController
@RequestMapping("/api/v1/me/tenants")
public class OidcTenantDiscoveryController {
    private final RequestContextResolver context;
    private final TenantRepository tenantRepository;

    public OidcTenantDiscoveryController(RequestContextResolver context, TenantRepository tenantRepository) {
        this.context = context;
        this.tenantRepository = tenantRepository;
    }

    @GetMapping
    public ResponseEntity<List<TenantChoice>> list() {
        if (context.securityMode() != SecurityMode.OIDC) {
            throw new SecurityContextException("Tenant discovery requires OIDC authentication");
        }
        var memberships = context.resolveAvailableOidcTenants();
        List<TenantChoice> choices = memberships.entrySet().stream()
                .map(e -> tenantRepository.findById(e.getKey())
                        .filter(t -> t.status() == TenantStatus.ACTIVE)
                        .map(t -> new TenantChoice(t.id().value().toString(), t.name(), t.slug(), e.getValue()))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(TenantChoice::tenantId))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(choices);
    }

    public record TenantChoice(String tenantId, String name, String slug, Set<String> roles) {}
}
