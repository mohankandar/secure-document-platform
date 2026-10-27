package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.DelegatedRoleGrantPolicy;
import com.securedoc.platform.api.security.LocalDemoUserStore;
import com.securedoc.platform.api.security.ProductAuthorizationPolicy;
import com.securedoc.platform.api.security.SecurityMode;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.exception.AccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** LOCAL-only demo management; deliberately not available in OIDC mode. */
@RestController
@RequestMapping("/api/v1/admin/local-users")
@ConditionalOnProperty(name = "securedoc.security.mode", havingValue = "LOCAL")
public class LocalUsersController {
    private final RequestContextResolver context;
    private final LocalDemoUserStore store;
    private final ProductAuthorizationPolicy authorization = new ProductAuthorizationPolicy();
    private final DelegatedRoleGrantPolicy grant = new DelegatedRoleGrantPolicy();

    public LocalUsersController(RequestContextResolver context, LocalDemoUserStore store) {
        this.context = context;
        this.store = store;
    }

    @GetMapping
    public ResponseEntity<List<LocalDemoUserStore.DemoUser>> list(HttpServletRequest request) {
        var principal = context.resolvePrincipal(request);
        if (!authorization.has(principal, UserCapability.TENANT_AUTH_MANAGE)) {
            throw new AccessDeniedException("User administration requires tenant IT access");
        }
        return ResponseEntity.ok(store.list(principal.tenantId().value()));
    }

    @PutMapping("/{actorId}")
    public ResponseEntity<LocalDemoUserStore.DemoUser> assign(
            @PathVariable UUID actorId, @RequestBody Assignment body, HttpServletRequest request) {
        var principal = context.resolvePrincipal(request);
        UUID tenantId = principal.tenantId().value();
        if (body == null || body.displayName() == null || body.displayName().isBlank()
                || body.displayName().trim().length() > 120
                || !grant.mayAssign(principal.roles(), principal.actorId().value(),
                        actorId, tenantId, tenantId, body.roleName())) {
            throw new AccessDeniedException("Simulated role assignment is not permitted");
        }
        store.upsert(tenantId, actorId, body.displayName().trim(), body.roleName(), principal.actorId().value());
        return ResponseEntity.ok(store.find(tenantId, actorId).orElseThrow());
    }

    public record Assignment(String displayName, String roleName) {}
}
