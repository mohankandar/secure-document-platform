package com.securedoc.platform.api.security;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.security.oidc.OidcRoleMapper;
import com.securedoc.platform.api.security.oidc.TrustedActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestContextResolverSecurityTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void securityModeDefaultsToOidcFailClosedDirection() {
        assertEquals(SecurityMode.OIDC, new SecurityProperties().getMode());
    }

    @Test
    void oidcUsesVerifiedClaimsAndIgnoresSpoofedIdentityHeaders() {
        SecurityProperties properties = oidcProperties();
        RequestContextResolver resolver = resolver(properties);
        Jwt jwt = jwt("00000000-0000-0000-0000-000000000001", List.of("securedoc-business-admin"));
        SecurityContextHolder.getContext().setAuthentication(authenticated(jwt));

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Tenant-Id")).thenReturn("00000000-0000-0000-0000-000000000002");
        when(request.getHeader("X-Actor-Roles")).thenReturn("ADMIN");
        when(request.getHeader("X-Actor-Id")).thenReturn("00000000-0000-0000-0000-000000000099");

        var principal = resolver.resolvePrincipal(request);
        assertEquals("00000000-0000-0000-0000-000000000001", principal.tenantId().toString());
        assertEquals(java.util.Set.of("BUSINESS_ADMIN"), principal.roles());
        assertFalse(principal.hasRole("ADMIN"));
        assertEquals("person@example.test", principal.username());
    }

    @Test
    void oidcFailsClosedForMissingTenantOrUnmappedRole() {
        SecurityProperties properties = oidcProperties();
        RequestContextResolver resolver = resolver(properties);
        SecurityContextHolder.getContext().setAuthentication(authenticated(jwt(null, List.of("securedoc-user"))));
        assertThrows(SecurityContextException.class, () -> resolver.resolvePrincipal(mock(HttpServletRequest.class)));

        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(authenticated(
                jwt("00000000-0000-0000-0000-000000000001", List.of("unknown-group"))));
        assertThrows(SecurityContextException.class, () -> resolver.resolvePrincipal(mock(HttpServletRequest.class)));
    }

    @Test
    void localModeRequiresExplicitAdminRoleInsteadOfDefaultingToAdmin() {
        SecurityProperties properties = new SecurityProperties();
        properties.setMode(SecurityMode.LOCAL);
        RequestContextResolver resolver = resolver(properties);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        var principal = resolver.resolvePrincipal(request);
        assertEquals(java.util.Set.of("USER"), principal.roles());
    }

    private JwtAuthenticationToken authenticated(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_AUTHENTICATED")));
    }

    private RequestContextResolver resolver(SecurityProperties properties) {
        return new RequestContextResolver(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000099",
                properties,
                new OidcRoleMapper(properties),
                new TrustedActorResolver());
    }

    private SecurityProperties oidcProperties() {
        SecurityProperties properties = new SecurityProperties();
        properties.setMode(SecurityMode.OIDC);
        properties.getOidc().getRoleMappings().put("USER", "securedoc-user");
        properties.getOidc().getRoleMappings().put("BUSINESS_ADMIN", "securedoc-business-admin");
        properties.getOidc().getRoleMappings().put("PLATFORM_ADMIN", "securedoc-platform-admin");
        properties.getOidc().getRoleMappings().put("ADMIN", "securedoc-admin");
        return properties;
    }

    private Jwt jwt(String tenantId, List<String> groups) {
        Jwt.Builder builder = Jwt.withTokenValue("test")
                .header("alg", "none")
                .issuer("https://identity.example.test")
                .subject("subject-123")
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("preferred_username", "person@example.test")
                .claim("groups", groups);
        if (tenantId != null) builder.claim("tenant_id", tenantId);
        return builder.build();
    }
}
