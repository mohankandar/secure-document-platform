package com.securedoc.platform.api.policy;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.controller.PolicyEstimateController;
import com.securedoc.platform.api.security.ProductAuthorizationPolicy;
import com.securedoc.platform.api.security.ProductRole;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.business.admin.PolicyEstimateService;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.PolicyEstimateReadPort;
import com.securedoc.platform.application.port.out.PolicyEstimateReadPort.Bucket;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 0019 B2 at the API boundary: the response shape, the entity from the request, the capability, and that no
 * counts are given under 20 cases.
 */
class PolicyEstimateApiTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final TenantId entity = TenantId.of(UUID.randomUUID());
    private final List<TenantId> askedFor = new ArrayList<>();
    private PolicyEstimateReadPort.Facts facts;

    private PolicyEstimateController controller(TenantId tenant) {
        TenantRedactionPolicyRepository policies = new TenantRedactionPolicyRepository() {
            public Optional<TenantRedactionPolicy> findByTenantId(TenantId id) {
                return Optional.of(new TenantRedactionPolicy(id, Set.of(EntityType.SSN), 0.80, ReviewMode.UNCERTAIN_ONLY, true, NOW));
            }
            public void save(TenantRedactionPolicy p) { }
        };
        PolicyEstimateReadPort port = (t, since, until) -> { askedFor.add(t); return facts; };
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(ActorId.generate(), tenant, "ba@example.test",
                Set.of(ProductRole.BUSINESS_ADMIN.name()));
        RequestContextResolver resolver = mock(RequestContextResolver.class);
        when(resolver.resolvePrincipal(any())).thenReturn(principal);
        return new PolicyEstimateController(
                new PolicyEstimateService(policies, port, Clock.fixed(NOW, ZoneOffset.UTC)), resolver);
    }

    @SuppressWarnings("unchecked")
    @Test
    void theResponseCarriesBothSidesForTheSignedInEntity() {
        // 512 cases: 148 with a finding below 0.80 or unlocated, 364 more whose lowest finding is at 0.80 or above.
        facts = new PolicyEstimateReadPort.Facts(512,
                List.of(new Bucket(PolicyEstimateReadPort.UNLOCATED, 40), new Bucket(0.62, 300),
                        new Bucket(0.91, 2070)),
                List.of(new Bucket(PolicyEstimateReadPort.UNLOCATED, 30), new Bucket(0.62, 118), new Bucket(0.91, 364)));
        ResponseEntity<Map<String, Object>> response = controller(entity)
                .estimate("REVIEW_ALL", null, mock(HttpServletRequest.class));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        Map<String, Object> body = response.getBody();
        assertEquals(List.of("windowDays", "cases", "enoughData", "current", "proposed"), List.copyOf(body.keySet()));
        assertEquals(30, body.get("windowDays"));
        assertEquals(512L, body.get("cases"));
        assertEquals(true, body.get("enoughData"));
        Map<String, Object> current = (Map<String, Object>) body.get("current");
        assertEquals(Map.of("reviewMode", "UNCERTAIN_ONLY", "threshold", 0.80, "preset", "BALANCED",
                "casesToPerson", 148L, "findingsToPerson", 340L), current);
        Map<String, Object> proposed = (Map<String, Object>) body.get("proposed");
        assertEquals("REVIEW_ALL", proposed.get("reviewMode"));
        assertTrue(proposed.containsKey("threshold"));
        assertEquals(null, proposed.get("threshold"));
        assertEquals("STRICT", proposed.get("preset"));
        assertEquals(512L, proposed.get("casesToPerson"));
        assertEquals(2410L, proposed.get("findingsToPerson"));
        assertEquals(List.of(entity), askedFor, "the entity comes from the request, never from a parameter");
    }

    @SuppressWarnings("unchecked")
    @Test
    void underTwentyCasesTheCountsAreLeftOut() {
        facts = new PolicyEstimateReadPort.Facts(19, List.of(new Bucket(0.5, 3)), List.of(new Bucket(0.5, 3)));
        Map<String, Object> body = controller(entity).estimate("UNCERTAIN_ONLY", "0.70", mock(HttpServletRequest.class)).getBody();
        assertEquals(false, body.get("enoughData"));
        assertEquals(19L, body.get("cases"));
        for (String side : List.of("current", "proposed")) {
            Map<String, Object> s = (Map<String, Object>) body.get(side);
            assertFalse(s.containsKey("casesToPerson"), side);
            assertFalse(s.containsKey("findingsToPerson"), side);
        }
        assertEquals("LIGHT", ((Map<String, Object>) body.get("proposed")).get("preset"));
    }

    @Test
    void invalidValuesAreRefused() {
        facts = new PolicyEstimateReadPort.Facts(0, List.of(), List.of());
        PolicyEstimateController c = controller(entity);
        assertThrows(ValidationException.class, () -> c.estimate(null, "0.8", mock(HttpServletRequest.class)));
        assertThrows(ValidationException.class, () -> c.estimate("UNCERTAIN_ONLY", "1.5", mock(HttpServletRequest.class)));
        assertTrue(askedFor.isEmpty(), "nothing is read for a refused request");
    }

    @Test
    void needsTenantPolicyManageWhichOnlyTheBusinessAdministratorHas() throws Exception {
        RequireCapability required = PolicyEstimateController.class
                .getMethod("estimate", String.class, String.class, HttpServletRequest.class)
                .getAnnotation(RequireCapability.class);
        assertArrayEquals(new UserCapability[]{UserCapability.TENANT_POLICY_MANAGE}, required.value());
        ProductAuthorizationPolicy policy = new ProductAuthorizationPolicy();
        for (ProductRole role : ProductRole.values()) {
            AuthenticatedPrincipal p = AuthenticatedPrincipal.of(ActorId.generate(), entity, "x@example.test", Set.of(role.name()));
            assertEquals(role == ProductRole.BUSINESS_ADMIN, policy.has(p, UserCapability.TENANT_POLICY_MANAGE), role.name());
        }
    }
}
