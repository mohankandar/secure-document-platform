package com.securedoc.platform.api.security;

import com.documentplatform.identity.IdentityClaimConfig;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.security.oidc.OidcTenantMembershipResolver;
import com.securedoc.platform.api.security.oidc.TrustedActorResolver;
import com.securedoc.platform.application.audit.RequestActorDisplayName;
import com.securedoc.platform.application.port.out.ActiveOidcMachineTenantBindings;
import com.securedoc.platform.application.port.out.ActiveOidcTenantCandidates;
import com.securedoc.platform.application.port.out.TenantOidcGroupGrantRepository;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Phase 2 step A1 (redone in task 0037 step 0): the shared-issuer stores are read once per request, however many times the principal is asked for. */
class RequestContextResolverOncePerRequestTest {

    private static final TenantId A = TenantId.of(UUID.fromString("a1a1a1a1-0000-4000-8000-00000000000a"));
    private static final String ISSUER = "http://localhost:18181/realms/lexavault-phase1";

    private final AtomicInteger grantLookups = new AtomicInteger();
    private final AtomicInteger candidateLookups = new AtomicInteger();
    private final AtomicInteger machineLookups = new AtomicInteger();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private RequestContextResolver resolver(Map<TenantId, Map<String, Set<String>>> grants) {
        SecurityProperties properties = new SecurityProperties();
        properties.setMode(SecurityMode.OIDC);
        properties.getOidc().setProviderId("phase1-oidc");
        properties.getOidc().setAudience("lexavault-phase1-api");
        properties.getOidc().setIssuerUri(ISSUER);
        properties.getOidc().setPersistentConnectionEnabled(true);
        TenantOidcGroupGrantRepository grantStore = (p, i, a) -> { grantLookups.incrementAndGet(); return grants; };
        ActiveOidcTenantCandidates candidateStore = (p, i, a) -> { candidateLookups.incrementAndGet(); return Set.of(A); };
        ActiveOidcMachineTenantBindings machineStore = (p, i, a, c) -> { machineLookups.incrementAndGet(); return Set.of(A); };
        return new RequestContextResolver("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000099",
                properties,
                new OidcTenantMembershipResolver(grantStore, candidateStore, properties), machineStore, new TrustedActorResolver(),
                new OidcJwtIdentityNormalizer("phase1-oidc", new IdentityClaimConfig(null, null, null, null)), displayName);
    }

    private final RequestActorDisplayName displayName = new RequestActorDisplayName();

    @Test
    void theDisplayNameIsBoundAgainWhenTheKeptPrincipalIsReused() {
        signedIn(List.of("lv-a-staff"));
        RequestContextResolver resolver = resolver(Map.of(A, Map.of("lv-a-staff", Set.of("DOCUMENT_SUPERVISOR"))));
        MockHttpServletRequest request = new MockHttpServletRequest();
        var principal = resolver.resolvePrincipal(request);
        displayName.clear();
        resolver.resolvePrincipal(request);
        assertEquals(Optional.of("alice.a"), displayName.displayNameFor(principal.actorId()));
        assertEquals(1, grantLookups.get());
    }

    private static void signedIn(List<String> groups) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").issuer(ISSUER).subject("subject-alice")
                .issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300))
                .claim("preferred_username", "alice.a").claim("groups", groups).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_AUTHENTICATED"))));
    }

    @Test
    void theStoresAreReadOncePerRequest() {
        signedIn(List.of("lv-a-staff"));
        RequestContextResolver resolver = resolver(Map.of(A, Map.of("lv-a-staff", Set.of("DOCUMENT_SUPERVISOR"))));
        MockHttpServletRequest request = new MockHttpServletRequest();
        var first = resolver.resolvePrincipal(request);   // tenant boundary interceptor
        var second = resolver.resolvePrincipal(request);  // capability interceptor
        var third = resolver.resolvePrincipal(request);   // controller
        assertSame(first, second);
        assertSame(first, third);
        assertEquals(1, grantLookups.get());
        assertEquals(1, candidateLookups.get());

        resolver.resolvePrincipal(new MockHttpServletRequest());
        assertEquals(2, grantLookups.get(), "a new request resolves again");
    }

    @Test
    void theMachinePrincipalIsAlsoResolvedOncePerRequest() {
        signedIn(List.of());
        RequestContextResolver resolver = resolver(Map.of());
        MockHttpServletRequest request = new MockHttpServletRequest();
        resolver.resolveIntegrationPrincipal(request);
        resolver.resolveIntegrationPrincipal(request);
        assertEquals(1, machineLookups.get());
    }

    @Test
    void aRefusalIsNotRememberedAsAPrincipal() {
        signedIn(List.of("lv-unmapped"));
        RequestContextResolver resolver = resolver(Map.of(A, Map.of("lv-a-staff", Set.of("DOCUMENT_SUPERVISOR"))));
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThrows(SecurityContextException.class, () -> resolver.resolvePrincipal(request));
        assertThrows(SecurityContextException.class, () -> resolver.resolvePrincipal(request));
        assertEquals(2, grantLookups.get(), "each refused call refuses again; nothing is stored");
    }
}
