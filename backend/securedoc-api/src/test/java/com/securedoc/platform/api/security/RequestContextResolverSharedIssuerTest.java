package com.securedoc.platform.api.security;

import com.documentplatform.identity.IdentityClaimConfig;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.security.oidc.OidcTenantMembershipResolver;
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
 * Sign-in through the entities' grants (task 0058: the only way in OIDC mode; until then behind a setting), including the
 * exact refusal messages. Uses only collaborators that stay in LexaVault: the resolver's constructor, the application ports, and the
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
        ActiveOidcMachineTenantBindings machineStore = new ActiveOidcMachineTenantBindings() {
            @Override
            public Set<TenantId> findEnabledPushTenants(String p, URI i, String a, String client) {
                return expected(p, i, a) ? machineBindings.getOrDefault(client, Set.of()) : Set.of();
            }

            @Override
            public Set<TenantId> findEnabledScannerTenants(String p, URI i, String a, String client) {
                return expected(p, i, a) ? machineBindings.getOrDefault("scanner:" + client, Set.of()) : Set.of();
            }
        };
        return new RequestContextResolver(
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000099",
                properties,
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
        signedIn(username, groups, Map.of());
    }

    private static void signedIn(String username, List<String> groups, Map<String, Object> more) {
        Jwt jwt = Jwt.withTokenValue("pinned").header("alg", "none")
                .issuer(ISSUER).subject("subject-" + username)
                .issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300))
                .claim("preferred_username", username).claim("groups", groups)
                .claims(claims -> claims.putAll(more))
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

    // ------------------------------------------------------------------ platform-wide grants (task 0054)

    private static RequestContextResolver withPlatformGrants(Map<String, Set<String>> platformGrants, Set<TenantId> activeCandidates) {
        SecurityProperties properties = sharedIssuerProperties();
        return new RequestContextResolver(
                "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000099", properties,
                new OidcTenantMembershipResolver((p, i, a) -> expected(p, i, a) ? GRANTS : Map.of(),
                        (p, i, a) -> expected(p, i, a) ? activeCandidates : Set.of(),
                        (p, i, a) -> expected(p, i, a) ? platformGrants : Map.of(), properties),
                (p, i, a, client) -> Set.of(), new TrustedActorResolver(),
                new OidcJwtIdentityNormalizer(properties.getOidc().getProviderId(),
                        new IdentityClaimConfig(properties.getOidc().getUsernameClaim(), properties.getOidc().getEmailClaim(),
                                properties.getOidc().getDisplayNameClaim(), properties.getOidc().getRolesClaim())));
    }

    private static final Map<String, Set<String>> PLATFORM = Map.of("lv-ops", Set.of("PLATFORM_OPERATIONS_ADMIN"));

    @Test
    void aPlatformWideGrantGivesThePlatformRoleInEveryActiveEntityOnTopOfItsOwnRoles() {
        signedIn("olga.ops", List.of("lv-ops", "lv-a-staff"));
        RequestContextResolver resolver = withPlatformGrants(PLATFORM, Set.of(A, B));
        assertEquals(Map.of(A, Set.of("DOCUMENT_SUPERVISOR", "PLATFORM_OPERATIONS_ADMIN"), B, Set.of("PLATFORM_OPERATIONS_ADMIN")),
                resolver.resolveAvailableOidcTenants());
        assertEquals(Set.of("PLATFORM_OPERATIONS_ADMIN"), resolver.resolvePrincipal(choosing(B.value().toString())).roles());
        assertEquals(Set.of("DOCUMENT_SUPERVISOR", "PLATFORM_OPERATIONS_ADMIN"),
                resolver.resolvePrincipal(choosing(A.value().toString())).roles());
        assertRefused("A unique authorized tenant context is required", () -> resolver.resolvePrincipal(choosing(null)));
    }

    @Test
    void aPlatformWideGrantNeverOpensAnEntityThatIsNotActive() {
        signedIn("olga.ops", List.of("lv-ops"));
        RequestContextResolver resolver = withPlatformGrants(PLATFORM, Set.of(A));
        assertEquals(Map.of(A, Set.of("PLATFORM_OPERATIONS_ADMIN")), resolver.resolveAvailableOidcTenants());
        assertRefused("Authenticated identity is not authorized for the requested tenant",
                () -> resolver.resolvePrincipal(choosing(B.value().toString())));
    }

    @Test
    void withoutThePlatformGroupNothingChanges() {
        signedIn("alice.a", List.of("lv-a-staff"));
        assertEquals(Map.of(A, Set.of("DOCUMENT_SUPERVISOR")), withPlatformGrants(PLATFORM, Set.of(A, B)).resolveAvailableOidcTenants());
        // Group names are compared exactly (platform-core 0.7.0, SEC-44).
        for (String almost : List.of("LV-OPS", "lv-ops ", "/lv-ops", "lv-ops,lv-a-staff")) {
            signedIn("eve", List.of(almost));
            assertRefused("No verified ACTIVE tenant membership is available",
                    () -> withPlatformGrants(PLATFORM, Set.of(A, B)).resolvePrincipal(choosing(null)));
        }
    }

    @Test
    void aPlatformWideGrantOfAnEntityRoleOrAnUnknownNameIsRefused() {
        signedIn("olga.ops", List.of("lv-ops"));
        for (String role : List.of("BUSINESS_ADMIN", "DOCUMENT_SUPERVISOR")) {
            assertRefused("Only Platform Operations can be granted through a platform-wide grant",
                    () -> withPlatformGrants(Map.of("lv-ops", Set.of(role)), Set.of(A)).resolvePrincipal(choosing(null)));
        }
        for (String role : List.of("platform_operations_admin", " PLATFORM_OPERATIONS_ADMIN", "USER", "ROOT")) {
            assertRefused("Invalid tenant role configuration",
                    () -> withPlatformGrants(Map.of("lv-ops", Set.of(role)), Set.of(A)).resolvePrincipal(choosing(null)));
        }
    }

    @Test
    void platformWideGrantsThatCannotBeReadRefuseTheSignIn() {
        signedIn("alice.a", List.of("lv-a-staff"));
        assertRefused("Tenant has no trusted group admission policy",
                () -> withPlatformGrants(null, Set.of(A)).resolvePrincipal(choosing(null)));
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

    /** Task 0054 (platform-core 0.7.0, SEC-7): whatever user name a person's token names, it is no machine. */
    @Test
    void aPersonsTokenIsNeverAMachineCaller() {
        RequestContextResolver resolver = resolver(GRANTS, Set.of(A, B), Map.of("service-account-intake", Set.of(A)));
        for (Map.Entry<String, Object> person : Map.<String, Object>of("email", "alice@example.test", "name", "Alice",
                "given_name", "Alice", "auth_time", 1_700_000_000L, "groups", List.of("lv-a-staff"), "nonce", "n", "sid", "s").entrySet()) {
            signedIn("service-account-intake", List.of(), Map.of(person.getKey(), person.getValue()));
            assertRefused("A person's sign-in cannot be used as an integration client",
                    () -> resolver.resolveIntegrationPrincipal(choosing(null)));
        }
        signedIn("service-account-intake", List.of(), Map.of("azp", "intake", "client_id", "intake", "name", " "));
        assertEquals(A, resolver.resolveIntegrationPrincipal(choosing(null)).tenantId());
    }

    private static HttpServletRequest scanning() {
        HttpServletRequest request = choosing(B.value().toString());
        when(request.getServletPath()).thenReturn("/api/v1/scanner-intakes/x");
        return request;
    }

    /** Task 0075: the scanner routes take a scanner source's client only, the push routes a push source's only. */
    @Test
    void aScannerClientIsAMachineOnTheScannerRoutesOnlyAndAPushClientNever() {
        RequestContextResolver resolver = resolver(GRANTS, Set.of(A, B), Map.of("scanner:bridge", Set.of(A),
                "intake", Set.of(B), "scanner:two", Set.of(A, B)));
        String refused = "Machine identity has no unique ACTIVE tenant integration binding";
        signedIn("bridge", List.of());
        assertEquals(A, resolver.resolveIntegrationPrincipal(scanning()).tenantId(), "no header chooses the entity");
        assertRefused(refused, () -> resolver.resolveIntegrationPrincipal(choosing(null)));
        signedIn("intake", List.of());
        assertRefused(refused, () -> resolver.resolveIntegrationPrincipal(scanning()));
        signedIn("two", List.of());
        assertRefused(refused, () -> resolver.resolveIntegrationPrincipal(scanning()));
        signedIn("bridge", List.of("lv-a-staff"));
        assertRefused("A person's sign-in cannot be used as an integration client",
                () -> resolver.resolveIntegrationPrincipal(scanning()));
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

    /** Task 0058: one entity in the installation: the refusal belongs in its log. With two it belongs in none. */
    @Test
    void aRefusalIsInTheOnlyEntitysLogAndInNoneWhenThereAreTwo() {
        signedIn("dave.none", List.of("lv-unmapped"));
        SecurityContextException one = assertThrows(SecurityContextException.class, () -> phase1(Set.of(A)).resolvePrincipal(choosing(null)));
        assertEquals(A, one.tenantId());
        assertEquals("NO_VERIFIED_MEMBERSHIP", one.reasonCode());
        SecurityContextException two = assertThrows(SecurityContextException.class, () -> phase1(Set.of(A, B)).resolvePrincipal(choosing(null)));
        org.junit.jupiter.api.Assertions.assertNull(two.tenantId());
    }

    /** Task 0058: without the stored connection nobody gets in, person or machine; no claim or header takes its place. */
    @Test
    void withoutStoredConnectionsEverybodyIsRefused() {
        RequestContextResolver off = without();
        signedIn("alice.a", List.of("lv-a-staff"), Map.of("tenant_id", A.value().toString()));
        for (String choice : new String[] {null, A.value().toString()}) {
            assertEquals("SHARED_ISSUER_REQUIRES_PERSISTED_CONNECTIONS", assertThrows(SecurityContextException.class,
                    () -> off.resolvePrincipal(choosing(choice))).reasonCode());
        }
        assertThrows(SecurityContextException.class, off::resolveAvailableOidcTenants);
        signedIn("service-account-intake", List.of(), Map.of("tenant_id", A.value().toString()));
        assertEquals("MACHINE_IDENTITY_CONFIGURATION_INCOMPLETE", assertThrows(SecurityContextException.class,
                () -> off.resolveIntegrationPrincipal(choosing(null))).reasonCode());
    }

    private static RequestContextResolver without() {
        SecurityProperties properties = sharedIssuerProperties();
        properties.getOidc().setPersistentConnectionEnabled(false);
        return new RequestContextResolver("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000099", properties,
                new OidcTenantMembershipResolver((p, i, a) -> GRANTS, (p, i, a) -> Set.of(A), properties),
                (p, i, a, client) -> Set.of(A), new TrustedActorResolver(),
                new OidcJwtIdentityNormalizer(PROVIDER, new IdentityClaimConfig(null, null, null, null)));
    }
}
