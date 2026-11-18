package com.securedoc.platform.api.security;

import com.documentplatform.identity.IdentityClaimConfig;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.security.oidc.OidcRoleMapper;
import com.securedoc.platform.api.security.oidc.OidcTenantMembershipResolver;
import com.securedoc.platform.api.security.oidc.OidcTenantResolver;
import com.securedoc.platform.api.security.oidc.TrustedActorResolver;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.port.out.ActiveOidcMachineTenantBindings;
import com.securedoc.platform.application.port.out.ActiveOidcTenantCandidates;
import com.securedoc.platform.application.port.out.TenantOidcGroupGrantRepository;
import com.securedoc.platform.domain.identity.TenantId;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the shared-issuer branch of RequestContextResolver (Phase 1b step P2a): resolver-level behaviour with
 * securedoc.security.oidc.shared-issuer-tenant-selection-enabled=true, including the exact refusal messages.
 * Written against the code before the Enterprise Identity 0.3.0 switch; it must pass unchanged after it.
 * Uses only collaborators that stay in LexaVault: the resolver's constructor, the application ports, and the
 * membership resolver, role mapper, tenant resolver and actor resolver classes.
 */
class RequestContextResolverSharedIssuerTest {

    private static final TenantId A = TenantId.of(UUID.fromString("a1a1a1a1-0000-4000-8000-00000000000a"));
    private static final TenantId B = TenantId.of(UUID.fromString("b2b2b2b2-0000-4000-8000-00000000000b"));
    private static final String ISSUER = "http://localhost:18181/realms/lexavault-phase1";
    private static final String PROVIDER = "phase1-oidc";
    private static final String AUDIENCE = "lexavault-phase1-api";

    private static final Map<TenantId, Map<String, Set<String>>> GRANTS = Map.of(
            A, Map.of("lv-a-staff", Set.of("DOCUMENT_SUPERVISOR"), "lv-a-admin", Set.of("BUSINESS_ADMIN", "TENANT_IT_ADMIN")),
            B, Map.of("lv-b-staff", Set.of("DOCUMENT_SUPERVISOR"), "lv-b-admin", Set.of("BUSINESS_ADMIN", "TENANT_IT_ADMIN")));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ setup

    private static SecurityProperties sharedIssuerProperties() {
        SecurityProperties properties = new SecurityProperties();
        properties.setMode(SecurityMode.OIDC);
        properties.getOidc().setProviderId(PROVIDER);
        properties.getOidc().setAudience(AUDIENCE);
        properties.getOidc().setIssuerUri(ISSUER);
        properties.getOidc().setPersistentConnectionEnabled(true);
        properties.getOidc().setSharedIssuerTenantSelectionEnabled(true);
        return properties;
    }

    private static boolean expected(String provider, URI issuer, String audience) {
        return PROVIDER.equals(provider) && URI.create(ISSUER).equals(issuer) && AUDIENCE.equals(audience);
    }

    private static RequestContextResolver resolver(Map<TenantId, Map<String, Set<String>>> grants, Set<TenantId> activeCandidates,
                                                   Map<String, Set<TenantId>> machineBindings) {
        SecurityProperties properties = sharedIssuerProperties();
        TenantOidcGroupGrantRepository grantStore = (p, i, a) -> expected(p, i, a) ? grants : Map.of();
        ActiveOidcTenantCandidates candidateStore = (p, i, a) -> expected(p, i, a) ? activeCandidates : Set.of();
        ActiveOidcMachineTenantBindings machineStore = (p, i, a, client) ->
                expected(p, i, a) ? machineBindings.getOrDefault(client, Set.of()) : Set.of();
        return new RequestContextResolver(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000099",
                properties,
                new OidcRoleMapper(properties),
                new OidcTenantResolver(properties, (p, i, a) -> Optional.empty()),
                new OidcTenantMembershipResolver(grantStore, candidateStore, properties),
                machineStore,
                new TrustedActorResolver(),
                new OidcJwtIdentityNormalizer(properties.getOidc().getProviderId(),
                        new IdentityClaimConfig(properties.getOidc().getUsernameClaim(), properties.getOidc().getEmailClaim(),
                                properties.getOidc().getDisplayNameClaim(), properties.getOidc().getRolesClaim())));
    }

    private static RequestContextResolver phase1(Set<TenantId> activeCandidates) {
        return resolver(GRANTS, activeCandidates, Map.of());
    }

    private static void signedIn(String username, List<String> groups) {
        Jwt jwt = Jwt.withTokenValue("pinned").header("alg", "none")
                .issuer(ISSUER).subject("subject-" + username)
                .issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300))
                .claim("preferred_username", username).claim("groups", groups)
                .audience(List.of(AUDIENCE)).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"))));
    }

    private static HttpServletRequest choosing(String selectedTenant) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Selected-Tenant-Id")).thenReturn(selectedTenant);
        return request;
    }

    private static void assertRefused(String message, Runnable call) {
        SecurityContextException refused = assertThrows(SecurityContextException.class, call::run);
        assertEquals(message, refused.getMessage());
    }

    // ------------------------------------------------------------------ human path

    @Test
    void oneEntityOpensWithoutAChoice() {
        signedIn("alice.a", List.of("lv-a-staff", "lv-a-admin"));
        AuthenticatedPrincipal principal = phase1(Set.of(A, B)).resolvePrincipal(choosing(null));
        assertEquals(A, principal.tenantId());
        assertEquals(Set.of("DOCUMENT_SUPERVISOR", "BUSINESS_ADMIN", "TENANT_IT_ADMIN"), principal.roles());
        assertEquals("alice.a", principal.username());
    }

    @Test
    void twoEntitiesWithoutAChoiceAreRefused() {
        signedIn("carol.ab", List.of("lv-a-staff", "lv-b-staff"));
        assertRefused("A unique authorized tenant context is required", () -> phase1(Set.of(A, B)).resolvePrincipal(choosing(null)));
    }

    @Test
    void twoEntitiesWithAChoiceOpenOnlyThatEntityWithItsOwnRoles() {
        signedIn("carol.ab", List.of("lv-a-staff", "lv-b-staff", "lv-a-admin"));
        AuthenticatedPrincipal inB = phase1(Set.of(A, B)).resolvePrincipal(choosing(B.value().toString()));
        assertEquals(B, inB.tenantId());
        assertEquals(Set.of("DOCUMENT_SUPERVISOR"), inB.roles(), "A's admin group gives nothing in B");
        AuthenticatedPrincipal inA = phase1(Set.of(A, B)).resolvePrincipal(choosing(A.value().toString()));
        assertEquals(A, inA.tenantId());
        assertEquals(Set.of("DOCUMENT_SUPERVISOR", "BUSINESS_ADMIN", "TENANT_IT_ADMIN"), inA.roles());
    }

    @Test
    void aChoiceThePersonDoesNotHoldIsRefused() {
        signedIn("alice.a", List.of("lv-a-staff", "lv-a-admin"));
        assertRefused("Authenticated identity is not authorized for the requested tenant",
                () -> phase1(Set.of(A, B)).resolvePrincipal(choosing(B.value().toString())));
    }

    @Test
    void noEntityIsRefused() {
        signedIn("dave.none", List.of("lv-unmapped"));
        assertRefused("No verified ACTIVE tenant membership is available", () -> phase1(Set.of(A, B)).resolvePrincipal(choosing(null)));
        assertEquals(Map.of(), phase1(Set.of(A, B)).resolveAvailableOidcTenants());
    }

    @Test
    void anInactiveEntityIsNeitherListedNorEnterable() {
        signedIn("carol.ab", List.of("lv-a-staff", "lv-b-staff"));
        RequestContextResolver bInactive = phase1(Set.of(A));
        assertEquals(Map.of(A, Set.of("DOCUMENT_SUPERVISOR")), bInactive.resolveAvailableOidcTenants());
        assertRefused("Authenticated identity is not authorized for the requested tenant",
                () -> bInactive.resolvePrincipal(choosing(B.value().toString())));
        assertEquals(A, bInactive.resolvePrincipal(choosing(null)).tenantId());
    }

    @Test
    void forgedChoicesAreRefused() {
        signedIn("alice.a", List.of("lv-a-staff"));
        RequestContextResolver resolver = phase1(Set.of(A, B));
        assertRefused("Invalid selected tenant identifier", () -> resolver.resolvePrincipal(choosing("not-a-uuid")));
        assertRefused("Authenticated identity is not authorized for the requested tenant",
                () -> resolver.resolvePrincipal(choosing(UUID.randomUUID().toString())));
        assertRefused("Authenticated identity is not authorized for the requested tenant",
                () -> resolver.resolvePrincipal(choosing("00000000-0000-0000-0000-000000000001")));
    }

    @Test
    void discoveryListsBothEntitiesWithTheirOwnRoles() {
        signedIn("carol.ab", List.of("lv-a-staff", "lv-b-staff"));
        assertEquals(Map.of(A, Set.of("DOCUMENT_SUPERVISOR"), B, Set.of("DOCUMENT_SUPERVISOR")),
                phase1(Set.of(A, B)).resolveAvailableOidcTenants());
    }

    @Test
    void thePlatformRoleCannotComeThroughAnEntityGroup() {
        signedIn("alice.a", List.of("lv-a-staff"));
        RequestContextResolver resolver = resolver(Map.of(A, Map.of("lv-a-staff", Set.of("PLATFORM_OPERATIONS_ADMIN"))), Set.of(A), Map.of());
        assertRefused("Platform Operations cannot be granted through tenant IdP groups", () -> resolver.resolvePrincipal(choosing(null)));
    }

    @Test
    void theActorIdIsStablePerIssuerAndSubject() {
        signedIn("alice.a", List.of("lv-a-staff"));
        var first = phase1(Set.of(A)).resolvePrincipal(choosing(null)).actorId();
        var second = phase1(Set.of(A, B)).resolvePrincipal(choosing(A.value().toString())).actorId();
        assertEquals(first, second);
    }

    // ------------------------------------------------------------------ machine path

    @Test
    void aMachineWithOneBindingResolvesToThatEntity() {
        signedIn("service-account-intake", List.of());
        RequestContextResolver resolver = resolver(GRANTS, Set.of(A, B), Map.of("service-account-intake", Set.of(A)));
        AuthenticatedPrincipal machine = resolver.resolveIntegrationPrincipal(choosing(B.value().toString()));
        assertEquals(A, machine.tenantId(), "a machine never uses a human entity choice");
        assertEquals("service-account-intake", machine.username());
        assertEquals(Set.of(), machine.roles());
    }

    @Test
    void aMachineWithTwoBindingsIsRefused() {
        signedIn("service-account-intake", List.of());
        RequestContextResolver resolver = resolver(GRANTS, Set.of(A, B), Map.of("service-account-intake", Set.of(A, B)));
        assertRefused("Machine identity has no unique ACTIVE tenant integration binding",
                () -> resolver.resolveIntegrationPrincipal(choosing(null)));
    }

    @Test
    void aMachineWithNoBindingIsRefused() {
        signedIn("service-account-unknown", List.of());
        RequestContextResolver resolver = resolver(GRANTS, Set.of(A, B), Map.of());
        assertRefused("Machine identity has no unique ACTIVE tenant integration binding",
                () -> resolver.resolveIntegrationPrincipal(choosing(null)));
    }
}
