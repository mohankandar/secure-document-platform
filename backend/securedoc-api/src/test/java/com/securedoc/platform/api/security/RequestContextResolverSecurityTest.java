package com.securedoc.platform.api.security;

import com.documentplatform.identity.IdentityClaimConfig;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.context.SecurityContextException;
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
    void oidcUsesTheEntityOfTheStoredConnectionAndFailsClosedForAGroupWithoutAGrant() {
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
        for (String role : List.of("BUSINESS_ADMIN", "TENANT_IT_ADMIN")) {
            SecurityContextHolder.clearContext();
            String external = switch (role) {
                case "BUSINESS_ADMIN" -> "securedoc-business-admin";
                default -> "securedoc-tenant-it-admin";
            };
            SecurityContextHolder.getContext().setAuthentication(authenticated(
                    jwt("00000000-0000-0000-0000-000000000001", List.of(external))));
            assertEquals(java.util.Set.of(role),
                    resolver(oidc).resolvePrincipal(mock(HttpServletRequest.class)).roles());
        }
    }

    /** Task 0054: with one configured entity too, the platform role comes through a platform-wide grant alone. */
    @Test
    void aPlatformWideGrantGivesThePlatformRoleInTheConfiguredEntity() {
        SecurityProperties oidc = oidcProperties();
        java.util.Map<String, java.util.Set<String>> grants = java.util.Map.of("ops", java.util.Set.of("PLATFORM_OPERATIONS_ADMIN"),
                "wrong", java.util.Set.of("BUSINESS_ADMIN"));
        RequestContextResolver resolver = resolver(oidc, (provider, issuer, audience) -> grants);
        signIn(List.of("ops"));
        assertEquals(java.util.Set.of("PLATFORM_OPERATIONS_ADMIN"), resolver.resolvePrincipal(mock(HttpServletRequest.class)).roles());
        signIn(List.of("ops", "securedoc-report-viewer"));
        assertEquals(java.util.Set.of("PLATFORM_OPERATIONS_ADMIN", "REPORT_VIEWER"),
                resolver.resolvePrincipal(mock(HttpServletRequest.class)).roles());
        signIn(List.of("securedoc-report-viewer"));
        assertEquals(java.util.Set.of("REPORT_VIEWER"), resolver.resolvePrincipal(mock(HttpServletRequest.class)).roles());
        // Refused: no role at all; an entity role through a platform-wide grant; grants that cannot be read.
        signIn(List.of("other"));
        assertThrows(SecurityContextException.class, () -> resolver.resolvePrincipal(mock(HttpServletRequest.class)));
        signIn(List.of("wrong", "securedoc-report-viewer"));
        assertThrows(SecurityContextException.class, () -> resolver.resolvePrincipal(mock(HttpServletRequest.class)));
        signIn(List.of("securedoc-report-viewer"));
        assertThrows(SecurityContextException.class,
                () -> resolver(oidc, (p, i, a) -> null).resolvePrincipal(mock(HttpServletRequest.class)));
    }

    /** Task 0054 (platform-core 0.7.0, SEC-44): group names count exactly as the login system sends them. */
    @Test
    void groupNamesAreComparedExactly() {
        SecurityProperties oidc = oidcProperties();
        for (String almost : List.of("securedoc-report-viewer ", " securedoc-report-viewer", "Securedoc-Report-Viewer",
                "securedoc-report-viewer,other", "other securedoc-report-viewer")) {
            signIn(List.of(almost));
            assertThrows(SecurityContextException.class, () -> resolver(oidc).resolvePrincipal(mock(HttpServletRequest.class)), almost);
        }
    }

    private void signIn(List<String> groups) {
        SecurityContextHolder.getContext().setAuthentication(authenticated(jwt("00000000-0000-0000-0000-000000000001", groups)));
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

    @Test
    void anUnmappedRoleRefusalCarriesItsReasonAndTheTrustedEntityAndPerson() {
        // Task 0023 C5: the refusal names a fixed reason and the entity and person LexaVault already trusts.
        SecurityProperties oidc = oidcProperties();
        Jwt jwt = jwt("00000000-0000-0000-0000-000000000001", List.of("unknown-group"));
        SecurityContextHolder.getContext().setAuthentication(authenticated(jwt));
        RequestContextResolver resolver = resolver(oidc);

        SecurityContextException refused = assertThrows(SecurityContextException.class,
                () -> resolver.resolvePrincipal(mock(HttpServletRequest.class)));
        assertEquals("NO_VERIFIED_MEMBERSHIP", refused.reasonCode());
        assertEquals("00000000-0000-0000-0000-000000000001", refused.tenantId().toString());
        assertEquals(new TrustedActorResolver().resolve(jwt), refused.actorId());
        assertEquals(Optional.of(new TrustedActorResolver().resolve(jwt)), resolver.currentOidcActor());
    }

    @Test
    void aRefusalBeforeAnyEntityIsKnownHasAReasonButNoEntity() {
        SecurityProperties oidc = oidcProperties();
        RequestContextResolver resolver = resolver(oidc);
        // No authenticated token at all.
        SecurityContextException refused = assertThrows(SecurityContextException.class,
                () -> resolver.resolvePrincipal(mock(HttpServletRequest.class)));
        assertEquals("OIDC_IDENTITY_REQUIRED", refused.reasonCode());
        assertNull(refused.tenantId());
        assertNull(refused.actorId());
        assertEquals(Optional.empty(), resolver.currentOidcActor());
    }

    private JwtAuthenticationToken authenticated(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_AUTHENTICATED")));
    }

    private RequestContextResolver resolver(SecurityProperties properties) {
        return resolver(properties, (provider, issuer, audience) -> java.util.Map.of());
    }

    private RequestContextResolver resolver(SecurityProperties properties,
                                            com.securedoc.platform.application.port.out.PlatformOidcGroupGrants platformGrants) {
        return new RequestContextResolver(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000099",
                properties,
                new OidcTenantMembershipResolver((p, i, a) -> expected(properties, p, i, a) ? java.util.Map.of(ENTITY, GRANTS) : java.util.Map.of(),
                        (p, i, a) -> expected(properties, p, i, a) ? java.util.Set.of(ENTITY) : java.util.Set.of(), platformGrants, properties),
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

    private static final com.securedoc.platform.domain.identity.TenantId ENTITY = com.securedoc.platform.domain.identity.TenantId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000001"));

    /** Task 0058: the entity's grants (roles as platform-identity's store gives them) take the place of the configured mapping. */
    private static final java.util.Map<String, java.util.Set<String>> GRANTS = java.util.Map.of(
            "securedoc-document-processor", java.util.Set.of("DOCUMENT_PROCESSOR"), "securedoc-user", java.util.Set.of("DOCUMENT_PROCESSOR"),
            "securedoc-reviewer", java.util.Set.of("DOCUMENT_PROCESSOR"), "securedoc-approver", java.util.Set.of("DOCUMENT_SUPERVISOR"),
            "securedoc-document-supervisor", java.util.Set.of("DOCUMENT_SUPERVISOR"), "securedoc-business-admin", java.util.Set.of("BUSINESS_ADMIN"),
            "securedoc-tenant-it-admin", java.util.Set.of("TENANT_IT_ADMIN"), "securedoc-report-viewer", java.util.Set.of("REPORT_VIEWER"));

    private static boolean expected(SecurityProperties properties, String provider, URI issuer, String audience) {
        return properties.getOidc().getProviderId().equals(provider) && URI.create("https://identity.example.test").equals(issuer)
                && properties.getOidc().getAudience().equals(audience);
    }

    private SecurityProperties oidcProperties() {
        SecurityProperties properties = new SecurityProperties();
        properties.setMode(SecurityMode.OIDC);
        properties.getOidc().setAudience("lexavault-api");
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
