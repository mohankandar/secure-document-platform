package com.securedoc.platform.api.context;

import com.securedoc.platform.api.security.SecurityMode;
import com.securedoc.platform.api.security.SecurityProperties;
import com.securedoc.platform.api.security.oidc.OidcRoleMapper;
import com.securedoc.platform.api.security.oidc.TrustedActorResolver;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the application principal from the configured trust boundary.
 * LOCAL may use explicit demo headers/fallbacks. OIDC uses verified JWT claims only.
 */
@Component
public class RequestContextResolver {

    private static final String HEADER_TENANT_ID = "X-Tenant-Id";
    private static final String HEADER_ACTOR_ID = "X-Actor-Id";
    private static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    private static final String HEADER_ACTOR_ROLES = "X-Actor-Roles";

    private static final Set<String> VALID_LOCAL_ROLES = Set.of(
            "USER", "REVIEWER", "APPROVER", "BUSINESS_ADMIN", "PLATFORM_ADMIN", "ADMIN");

    private final UUID demoTenantId;
    private final UUID demoActorId;
    private final SecurityProperties securityProperties;
    private final OidcRoleMapper oidcRoleMapper;
    private final TrustedActorResolver trustedActorResolver;

    public RequestContextResolver(
            @Value("${securedoc.demo.tenant-id}") String demoTenantId,
            @Value("${securedoc.demo.actor-id}") String demoActorId,
            SecurityProperties securityProperties,
            OidcRoleMapper oidcRoleMapper,
            TrustedActorResolver trustedActorResolver) {
        this.demoTenantId = UUID.fromString(demoTenantId);
        this.demoActorId = UUID.fromString(demoActorId);
        this.securityProperties = securityProperties;
        this.oidcRoleMapper = oidcRoleMapper;
        this.trustedActorResolver = trustedActorResolver;
    }

    public AuthenticatedPrincipal resolvePrincipal(HttpServletRequest request) {
        return securityProperties.getMode() == SecurityMode.LOCAL
                ? resolveLocalPrincipal(request)
                : resolveOidcPrincipal();
    }

    public SecurityMode securityMode() {
        return securityProperties.getMode();
    }

    public CorrelationId resolveCorrelationId(HttpServletRequest request) {
        String header = request.getHeader(HEADER_CORRELATION_ID);
        if (header != null && !header.isBlank()) {
            try {
                return CorrelationId.of(UUID.fromString(header.trim()));
            } catch (IllegalArgumentException ignored) {
                // Invalid correlation metadata is replaced, never used as identity truth.
            }
        }
        return CorrelationId.generate();
    }

    private AuthenticatedPrincipal resolveLocalPrincipal(HttpServletRequest request) {
        UUID tenantId = resolveLocalUuid(request, HEADER_TENANT_ID, demoTenantId);
        UUID actorId = resolveLocalUuid(request, HEADER_ACTOR_ID, demoActorId);
        Set<String> roles = resolveLocalRoles(request);
        return AuthenticatedPrincipal.of(
                ActorId.of(actorId), TenantId.of(tenantId), "demo-user", roles);
    }

    private AuthenticatedPrincipal resolveOidcPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !authentication.isAuthenticated()) {
            throw new SecurityContextException("Authenticated OIDC identity is required");
        }
        var jwt = token.getToken();
        String tenantClaim = securityProperties.getOidc().getTenantClaim();
        String rawTenant = jwt.getClaimAsString(tenantClaim);
        UUID tenantId;
        try {
            tenantId = UUID.fromString(rawTenant == null ? "" : rawTenant.trim());
        } catch (IllegalArgumentException ex) {
            throw new SecurityContextException("Authenticated identity has an invalid tenant claim");
        }

        Set<String> roles = oidcRoleMapper.map(jwt.getClaim(securityProperties.getOidc().getRolesClaim()));
        ActorId actorId = trustedActorResolver.resolve(jwt);
        String username = jwt.getClaimAsString(securityProperties.getOidc().getUsernameClaim());
        if (username == null || username.isBlank()) username = jwt.getSubject();
        if (username == null || username.isBlank()) {
            throw new SecurityContextException("Authenticated identity has no usable username/subject");
        }

        return AuthenticatedPrincipal.of(actorId, TenantId.of(tenantId), username.trim(), roles);
    }

    private UUID resolveLocalUuid(HttpServletRequest request, String headerName, UUID fallback) {
        String header = request.getHeader(headerName);
        if (header != null && !header.isBlank()) {
            try {
                return UUID.fromString(header.trim());
            } catch (IllegalArgumentException e) {
                throw new SecurityContextException("Invalid " + headerName + " header: must be a valid UUID");
            }
        }
        return fallback;
    }

    private Set<String> resolveLocalRoles(HttpServletRequest request) {
        String header = request.getHeader(HEADER_ACTOR_ROLES);
        if (header == null || header.isBlank()) {
            return Set.of("USER");
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String raw : header.split(",")) {
            String role = raw.trim().toUpperCase(Locale.ROOT);
            if (role.isEmpty()) continue;
            if (!VALID_LOCAL_ROLES.contains(role)) {
                throw new SecurityContextException("Invalid role in X-Actor-Roles header: " + raw.trim());
            }
            roles.add(role);
        }
        return roles.isEmpty() ? Set.of("USER") : Set.copyOf(roles);
    }
}
