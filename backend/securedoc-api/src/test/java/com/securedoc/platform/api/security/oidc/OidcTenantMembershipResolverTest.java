package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.security.SecurityProperties;
import com.securedoc.platform.application.port.out.TenantOidcGroupGrantRepository;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OidcTenantMembershipResolverTest {
    private static final TenantId A = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final TenantId B = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000002"));
    private static final URI ISSUER = URI.create("https://identity.example.test/realms/shared");

    private Jwt jwt() {
        return Jwt.withTokenValue("test-token").header("alg", "RS256")
                .issuer(ISSUER).subject("subject-1")
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2028-01-01T00:00:00Z")).build();
    }

    @Test void isolatesRolesEvenWhenIdpGroupsContainMultipleTenants() {
        TenantOidcGroupGrantRepository repository = mock(TenantOidcGroupGrantRepository.class);
        var policy = Map.of(A, Map.of("tenant-a", Set.of("DOCUMENT_PROCESSOR")),
                B, Map.of("tenant-b", Set.of("TENANT_IT_ADMIN")));
        when(repository.activeGrants("customer-oidc", ISSUER, "lexavault-api")).thenReturn(policy);
        SecurityProperties properties = new SecurityProperties();
        properties.getOidc().setAudience("lexavault-api");
        OidcTenantMembershipResolver resolver = new OidcTenantMembershipResolver(repository, properties);
        var onlyA = resolver.authorizedTenants(jwt(), Set.of("tenant-a"));
        assertEquals(Set.of(A), onlyA.keySet());
        assertEquals(Set.of("DOCUMENT_PROCESSOR"), onlyA.get(A));
        assertThrows(SecurityContextException.class, () -> resolver.select(B, onlyA));
        var both = resolver.authorizedTenants(jwt(), Set.of("tenant-a", "tenant-b"));
        assertThrows(SecurityContextException.class, () -> resolver.selectOnlyIfUnambiguous(both));
        assertEquals(Set.of("TENANT_IT_ADMIN"), both.get(B));
    }

    @Test void invalidGrantPolicyIsNotSilentlyTreatedAsNonMembership() {
        TenantOidcGroupGrantRepository repository = mock(TenantOidcGroupGrantRepository.class);
        when(repository.activeGrants("customer-oidc", ISSUER, "lexavault-api"))
                .thenReturn(Map.of(A, Map.of("tenant-a", Set.of("PLATFORM_OPERATIONS_ADMIN"))));
        SecurityProperties properties = new SecurityProperties();
        properties.getOidc().setAudience("lexavault-api");
        var resolver = new OidcTenantMembershipResolver(repository, properties);
        assertThrows(SecurityContextException.class,
                () -> resolver.authorizedTenants(jwt(), Set.of("tenant-a")));
    }

    @Test void noGroupsCannotAuthorizeAnyTenant() {
        TenantOidcGroupGrantRepository repository = mock(TenantOidcGroupGrantRepository.class);
        when(repository.activeGrants("customer-oidc", ISSUER, "lexavault-api"))
                .thenReturn(Map.of(A, Map.of("tenant-a", Set.of("DOCUMENT_PROCESSOR"))));
        SecurityProperties properties = new SecurityProperties();
        properties.getOidc().setAudience("lexavault-api");
        var resolver = new OidcTenantMembershipResolver(repository, properties);
        assertTrue(resolver.authorizedTenants(jwt(), Set.of()).isEmpty());
        assertThrows(SecurityContextException.class,
                () -> resolver.selectOnlyIfUnambiguous(resolver.authorizedTenants(jwt(), Set.of())));
    }
}
