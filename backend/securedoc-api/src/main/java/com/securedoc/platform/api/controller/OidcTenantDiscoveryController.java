package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.domain.identity.TenantId;
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

    public OidcTenantDiscoveryController(RequestContextResolver context) {
        this.context = context;
    }

    @GetMapping
    public ResponseEntity<List<TenantChoice>> list() {
        var memberships = context.resolveAvailableOidcTenants();
        List<TenantChoice> choices = memberships.entrySet().stream()
                .map(e -> new TenantChoice(e.getKey().value().toString(), e.getValue()))
                .sorted(Comparator.comparing(TenantChoice::tenantId))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(choices);
    }

    public record TenantChoice(String tenantId, Set<String> roles) {}
}
