package com.securedoc.platform.api.context;

import com.securedoc.platform.api.security.SecurityMode;
import com.securedoc.platform.api.security.SecurityProperties;
import com.securedoc.platform.api.security.ProductRole;
import com.securedoc.platform.api.security.oidc.OidcRoleMapper;
import com.securedoc.platform.api.security.oidc.TrustedActorResolver;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.observability.StructuredLogContext;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.api.observability.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
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
    private static final String HEADER_INTEGRATION_CLIENT = "X-Integration-Client";


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
        AuthenticatedPrincipal principal = securityProperties.getMode() == SecurityMode.LOCAL
                ? resolveLocalPrincipal(request)
                : resolveOidcPrincipal();
        StructuredLogContext.put("tenantId", principal.tenantId());
        StructuredLogContext.put("actorId", principal.actorId());
        return principal;
    }

    /**
     * Resolves a service/integration identity without requiring a product user-role mapping.
     * The caller is authorized later by binding this authenticated principal to an enabled
     * tenant PUSH_API Integration Connection. Production identity still comes only from the verified JWT.
     */
    public AuthenticatedPrincipal resolveIntegrationPrincipal(HttpServletRequest request) {
        AuthenticatedPrincipal principal = securityProperties.getMode() == SecurityMode.LOCAL
                ? resolveLocalIntegrationPrincipal(request)
                : resolveOidcIntegrationPrincipal();
        StructuredLogContext.put("tenantId", principal.tenantId());
        StructuredLogContext.put("actorId", principal.actorId());
        return principal;
    }

    public SecurityMode securityMode() {
        return securityProperties.getMode();
    }

    public CorrelationId resolveCorrelationId(HttpServletRequest request) {
        Object established = request.getAttribute(RequestCorrelationFilter.REQUEST_ATTRIBUTE_CORRELATION_ID);
        if (established instanceof UUID uuid) {
            StructuredLogContext.put("correlationId", uuid);
            return CorrelationId.of(uuid);
        }
        String header = request.getHeader(HEADER_CORRELATION_ID);
        if (header != null && !header.isBlank()) {
            try {
                UUID uuid = UUID.fromString(header.trim());
                StructuredLogContext.put("correlationId", uuid);
                return CorrelationId.of(uuid);
            } catch (IllegalArgumentException ignored) {
                // Invalid correlation metadata is replaced, never used as identity truth.
            }
        }
        CorrelationId generated = CorrelationId.generate();
        StructuredLogContext.put("correlationId", generated);
        return generated;
    }

    private AuthenticatedPrincipal resolveLocalPrincipal(HttpServletRequest request) {
        UUID tenantId = resolveLocalUuid(request, HEADER_TENANT_ID, demoTenantId);
        UUID actorId = resolveLocalUuid(request, HEADER_ACTOR_ID, demoActorId);
        Set<String> roles = resolveLocalRoles(request);
        return AuthenticatedPrincipal.of(
                ActorId.of(actorId), TenantId.of(tenantId), "demo-user", roles);
    }

    private AuthenticatedPrincipal resolveLocalIntegrationPrincipal(HttpServletRequest request) {
        UUID tenantId = resolveLocalUuid(request, HEADER_TENANT_ID, demoTenantId);
        UUID actorId = resolveLocalUuid(request, HEADER_ACTOR_ID, demoActorId);
        String client = request.getHeader(HEADER_INTEGRATION_CLIENT);
        if (client == null || client.isBlank()) client = "demo-integration-client";
        return AuthenticatedPrincipal.of(ActorId.of(actorId), TenantId.of(tenantId), client.trim(), Set.of());
    }

    private AuthenticatedPrincipal resolveOidcIntegrationPrincipal() {
        JwtAuthenticationToken token = requireOidcToken();
        var jwt = token.getToken();
        TenantId tenantId = resolveOidcTenant(jwt);
        ActorId actorId = trustedActorResolver.resolve(jwt);
        String username = resolveOidcUsername(jwt);
        return AuthenticatedPrincipal.of(actorId, tenantId, username, Set.of());
    }

    private AuthenticatedPrincipal resolveOidcPrincipal() {
        JwtAuthenticationToken token = requireOidcToken();
        var jwt = token.getToken();
        TenantId tenantId = resolveOidcTenant(jwt);
        Set<String> roles = oidcRoleMapper.map(jwt.getClaim(securityProperties.getOidc().getRolesClaim()));
        ActorId actorId = trustedActorResolver.resolve(jwt);
        return AuthenticatedPrincipal.of(actorId, tenantId, resolveOidcUsername(jwt), roles);
    }


    private JwtAuthenticationToken requireOidcToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !authentication.isAuthenticated()) {
            throw new SecurityContextException("Authenticated OIDC identity is required");
        }
        return token;
    }

    private TenantId resolveOidcTenant(org.springframework.security.oauth2.jwt.Jwt jwt) {
        String rawTenant = jwt.getClaimAsString(securityProperties.getOidc().getTenantClaim());
        try {
            return TenantId.of(UUID.fromString(rawTenant == null ? "" : rawTenant.trim()));
        } catch (IllegalArgumentException ex) {
            throw new SecurityContextException("Authenticated identity has an invalid tenant claim");
        }
    }

    private String resolveOidcUsername(org.springframework.security.oauth2.jwt.Jwt jwt) {
        String username = jwt.getClaimAsString(securityProperties.getOidc().getUsernameClaim());
        if (username == null || username.isBlank()) username = jwt.getSubject();
        if (username == null || username.isBlank()) {
            throw new SecurityContextException("Authenticated identity has no usable username/subject");
        }
        return username.trim();
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
            return Set.of(ProductRole.DOCUMENT_PROCESSOR.name());
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String raw : header.split(",")) {
            String role = raw.trim();
            if (role.isEmpty()) continue;
            ProductRole canonical = ProductRole.fromIdentityName(role)
                    .orElseThrow(() -> new SecurityContextException(
                            "Invalid role in X-Actor-Roles header: " + raw.trim()));
            roles.add(canonical.name());
        }
        return roles.isEmpty() ? Set.of(ProductRole.DOCUMENT_PROCESSOR.name()) : Set.copyOf(roles);
    }
}
