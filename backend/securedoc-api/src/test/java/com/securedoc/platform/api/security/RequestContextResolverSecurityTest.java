package com.securedoc.platform.api.security;

import com.documentplatform.identity.IdentityClaimConfig;
import com.documentplatform.identity.OidcConnectionBinding;
import com.documentplatform.identity.OidcConnectionBindingResolver;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.security.oidc.OidcRoleMapper;
import com.securedoc.platform.api.security.oidc.OidcTenantResolver;
import com.securedoc.platform.api.security.oidc.OidcTenantMembershipResolver;
import com.securedoc.platform.application.port.out.ActiveOidcMachineTenantBindings;
import com.securedoc.platform.application.port.out.ActiveOidcTenantCandidates;
import com.securedoc.platform.application.port.out.TenantOidcGroupGrantRepository;
import com.securedoc.platform.api.security.oidc.TrustedActorResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
        Jwt jwt = jwt("00000000-0000-0000-0000-000000000002", List.of("securedoc-business-admin"));
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
    void oidcUsesConnectionOwnedTenantAndFailsClosedForUnmappedRole() {
        SecurityProperties properties = oidcProperties();
        RequestContextResolver resolver = resolver(properties);
        SecurityContextHolder.getContext().setAuthentication(authenticated(jwt(null, List.of("securedoc-user"))));
        assertEquals("00000000-0000-0000-0000-000000000001",
                resolver.resolvePrincipal(mock(HttpServletRequest.class)).tenantId().toString());

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
        assertEquals(java.util.Set.of("DOCUMENT_PROCESSOR"), principal.roles());
    }

    @Test
    void retiredAdminFailsClosedForLocalAndOidcAuthentication() {
        SecurityProperties local = new SecurityProperties();
        local.setMode(SecurityMode.LOCAL);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Actor-Roles")).thenReturn("ADMIN");
        assertThrows(SecurityContextException.class, () -> resolver(local).resolvePrincipal(request));

        SecurityProperties oidc = oidcProperties();
        SecurityContextHolder.getContext().setAuthentication(authenticated(
                jwt("00000000-0000-0000-0000-000000000001", List.of("securedoc-admin"))));
        assertThrows(SecurityContextException.class,
                () -> resolver(oidc).resolvePrincipal(mock(HttpServletRequest.class)));
    }

    @Test
    void eachSupportedAdministratorRoleAuthenticatesWithoutLegacyAdmin() {
        SecurityProperties oidc = oidcProperties();
        for (String role : List.of("BUSINESS_ADMIN", "TENANT_IT_ADMIN", "PLATFORM_OPERATIONS_ADMIN")) {
            SecurityContextHolder.clearContext();
            String external = switch (role) {
                case "BUSINESS_ADMIN" -> "securedoc-business-admin";
                case "TENANT_IT_ADMIN" -> "securedoc-tenant-it-admin";
                default -> "securedoc-platform-operations-admin";
            };
            SecurityContextHolder.getContext().setAuthentication(authenticated(
                    jwt("00000000-0000-0000-0000-000000000001", List.of(external))));
            assertEquals(java.util.Set.of(role),
                    resolver(oidc).resolvePrincipal(mock(HttpServletRequest.class)).roles());
        }
    }

    @Test
    void retiredAdminCannotElevateAnOtherwiseAuthorizedOidcIdentity() {
        SecurityProperties oidc = oidcProperties();
        SecurityContextHolder.getContext().setAuthentication(authenticated(
                jwt("00000000-0000-0000-0000-000000000001",
                        List.of("securedoc-admin", "securedoc-tenant-it-admin"))));
        assertEquals(java.util.Set.of("TENANT_IT_ADMIN"),
                resolver(oidc).resolvePrincipal(mock(HttpServletRequest.class)).roles());
    }

    @Test
    void legacyAdminIsNotAValidProductIdentityRole() {
        assertTrue(ProductRole.fromIdentityName("ADMIN").isEmpty());
        assertTrue(ProductRole.fromIdentityName("admin").isEmpty());
    }

    @Test
    void reportViewerIsRecognizedAtLocalAndOidcTrustBoundaries() {
        SecurityProperties local = new SecurityProperties();
        local.setMode(SecurityMode.LOCAL);
        HttpServletRequest localRequest = mock(HttpServletRequest.class);
        when(localRequest.getHeader("X-Actor-Roles")).thenReturn("REPORT_VIEWER");
        assertEquals(java.util.Set.of("REPORT_VIEWER"), resolver(local).resolvePrincipal(localRequest).roles());

        SecurityProperties oidc = oidcProperties();
        SecurityContextHolder.getContext().setAuthentication(authenticated(
                jwt("00000000-0000-0000-0000-000000000001", List.of("securedoc-report-viewer"))));
        assertEquals(java.util.Set.of("REPORT_VIEWER"),
                resolver(oidc).resolvePrincipal(mock(HttpServletRequest.class)).roles());
    }


    @Test
    void legacyIdentityAliasesNormalizeImmediatelyToCanonicalRoles() {
        SecurityProperties local = new SecurityProperties();
        local.setMode(SecurityMode.LOCAL);
        HttpServletRequest localRequest = mock(HttpServletRequest.class);
        when(localRequest.getHeader("X-Actor-Roles")).thenReturn("USER,REVIEWER,APPROVER");
        assertEquals(java.util.Set.of("DOCUMENT_PROCESSOR", "DOCUMENT_SUPERVISOR"),
                resolver(local).resolvePrincipal(localRequest).roles());

        SecurityProperties oidc = oidcProperties();
        SecurityContextHolder.getContext().setAuthentication(authenticated(
                jwt("00000000-0000-0000-0000-000000000001", List.of("securedoc-reviewer", "securedoc-approver"))));
        assertEquals(java.util.Set.of("DOCUMENT_PROCESSOR", "DOCUMENT_SUPERVISOR"),
                resolver(oidc).resolvePrincipal(mock(HttpServletRequest.class)).roles());
    }

    @Test
    void canonicalIdentityGroupsMapWithoutLegacyNamesLeaking() {
        SecurityProperties oidc = oidcProperties();
        SecurityContextHolder.getContext().setAuthentication(authenticated(
                jwt("00000000-0000-0000-0000-000000000001", List.of("securedoc-document-processor"))));
        assertEquals(java.util.Set.of("DOCUMENT_PROCESSOR"),
                resolver(oidc).resolvePrincipal(mock(HttpServletRequest.class)).roles());
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
                new OidcTenantResolver(properties, persistedConnectionResolver(properties)),
                new OidcTenantMembershipResolver(mock(TenantOidcGroupGrantRepository.class),
                        mock(ActiveOidcTenantCandidates.class), properties),
                mock(ActiveOidcMachineTenantBindings.class),
                new TrustedActorResolver(),
                new OidcJwtIdentityNormalizer(
                        properties.getOidc().getProviderId(),
                        new IdentityClaimConfig(
                                properties.getOidc().getUsernameClaim(),
                                properties.getOidc().getEmailClaim(),
                                properties.getOidc().getDisplayNameClaim(),
                                properties.getOidc().getRolesClaim())));
    }

    private OidcConnectionBindingResolver persistedConnectionResolver(SecurityProperties properties) {
        return (providerId, issuer, audience) -> {
            if (!properties.getOidc().getProviderId().equals(providerId)
                    || !URI.create("https://identity.example.test").equals(issuer)
                    || !properties.getOidc().getAudience().equals(audience)) {
                return Optional.empty();
            }
            return Optional.of(new OidcConnectionBinding(
                    UUID.fromString("00000000-0000-0000-0000-000000000010"),
                    UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    providerId, issuer, audience, 1));
        };
    }

    private SecurityProperties oidcProperties() {
        SecurityProperties properties = new SecurityProperties();
        properties.setMode(SecurityMode.OIDC);
        properties.getOidc().setAudience("lexavault-api");
        properties.getOidc().setTenantId("00000000-0000-0000-0000-000000000001");
        properties.getOidc().getRoleMappings().put("DOCUMENT_PROCESSOR", "securedoc-document-processor");
        properties.getOidc().getRoleMappings().put("DOCUMENT_SUPERVISOR", "securedoc-document-supervisor");
        properties.getOidc().getRoleMappings().put("USER", "securedoc-user");
        properties.getOidc().getRoleMappings().put("REVIEWER", "securedoc-reviewer");
        properties.getOidc().getRoleMappings().put("APPROVER", "securedoc-approver");
        properties.getOidc().getRoleMappings().put("BUSINESS_ADMIN", "securedoc-business-admin");
        properties.getOidc().getRoleMappings().put("TENANT_IT_ADMIN", "securedoc-tenant-it-admin");
        properties.getOidc().getRoleMappings().put("PLATFORM_OPERATIONS_ADMIN", "securedoc-platform-operations-admin");
        properties.getOidc().getRoleMappings().put("REPORT_VIEWER", "securedoc-report-viewer");
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
