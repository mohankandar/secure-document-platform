package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.ProductAuthorizationPolicy;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.port.out.ProcessingIncidentRepository;
import com.securedoc.platform.application.port.out.SupportIncidentSearchRepository;
import com.securedoc.platform.application.support.model.SupportIncidentSearchCriteria;
import com.securedoc.platform.application.support.model.SupportIncidentSearchPage;
import com.securedoc.platform.application.support.service.SupportIncidentQueryService;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportIncidentControllerAuthorizationTest {

    @Test
    void tenantItAdminCrossTenantOverrideIsDeniedWithApplicationAccessDeniedException() {
        TenantId ownTenant = TenantId.of(UUID.randomUUID());
        UUID otherTenant = UUID.randomUUID();
        var principal = AuthenticatedPrincipal.of(
                ActorId.generate(), ownTenant, "tenant-it", Set.of("TENANT_IT_ADMIN"));
        RequestContextResolver resolver = mock(RequestContextResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(resolver.resolvePrincipal(request)).thenReturn(principal);

        var controller = new SupportIncidentController(
                new SupportIncidentQueryService(noopSearchRepository(), ProcessingIncidentRepository.none()),
                resolver,
                new ProductAuthorizationPolicy());

        assertThrows(AccessDeniedException.class, () -> controller.search(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, otherTenant, 0, 25, request));
    }

    @Test
    void platformOperationsOverrideScopesSearchToRequestedTenant() {
        TenantId ownTenant = TenantId.of(UUID.randomUUID());
        UUID otherTenant = UUID.randomUUID();
        var principal = AuthenticatedPrincipal.of(
                ActorId.generate(), ownTenant, "platform-ops", Set.of("PLATFORM_OPERATIONS_ADMIN"));
        RequestContextResolver resolver = mock(RequestContextResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(resolver.resolvePrincipal(request)).thenReturn(principal);

        AtomicReference<SupportIncidentSearchCriteria> captured = new AtomicReference<>();
        SupportIncidentSearchRepository repo = new SupportIncidentSearchRepository() {
            @Override public SupportIncidentSearchPage search(SupportIncidentSearchCriteria criteria) {
                captured.set(criteria);
                return new SupportIncidentSearchPage(List.of(), 0, criteria.page(), criteria.size());
            }
            @Override public Optional<com.securedoc.platform.application.support.model.SupportIncidentSummary> findByIncidentId(UUID incidentId, TenantId tenantId) {
                return Optional.empty();
            }
        };
        var controller = new SupportIncidentController(
                new SupportIncidentQueryService(repo, ProcessingIncidentRepository.none()),
                resolver,
                new ProductAuthorizationPolicy());

        controller.search(null, null, null, null, null, null, null, null, null,
                null, null, null, null, otherTenant, 0, 25, request);

        assertNotNull(captured.get());
        assertEquals(otherTenant, captured.get().tenantId().value());
    }

    @Test
    void springWiringUsesTheFourArgumentConstructor() throws Exception {
        var constructor = SupportIncidentController.class.getConstructor(
                SupportIncidentQueryService.class,
                com.securedoc.platform.application.support.service.SupportIncidentActionService.class,
                RequestContextResolver.class,
                ProductAuthorizationPolicy.class);

        assertNotNull(constructor.getAnnotation(org.springframework.beans.factory.annotation.Autowired.class));
    }

    @Test
    void combinedPlatformAndTenantItCannotMutateAnotherTenantIncident() {
        TenantId ownTenant = TenantId.of(UUID.randomUUID());
        UUID otherTenant = UUID.randomUUID();
        var principal = AuthenticatedPrincipal.of(
                ActorId.generate(), ownTenant, "dual-role", Set.of("TENANT_IT_ADMIN", "PLATFORM_OPERATIONS_ADMIN"));
        RequestContextResolver resolver = mock(RequestContextResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(resolver.resolvePrincipal(request)).thenReturn(principal);
        var controller = new SupportIncidentController(
                new SupportIncidentQueryService(noopSearchRepository(), ProcessingIncidentRepository.none()),
                resolver, new ProductAuthorizationPolicy());

        var action = new SupportIncidentController.IncidentActionRequest("PRESERVE_FOR_INVESTIGATION", 7, null);
        assertThrows(AccessDeniedException.class,
                () -> controller.action(UUID.randomUUID(), otherTenant, action, request));
    }

    private static SupportIncidentSearchRepository noopSearchRepository() {
        return new SupportIncidentSearchRepository() {
            @Override public SupportIncidentSearchPage search(SupportIncidentSearchCriteria criteria) {
                return new SupportIncidentSearchPage(List.of(), 0, criteria.page(), criteria.size());
            }
            @Override public Optional<com.securedoc.platform.application.support.model.SupportIncidentSummary> findByIncidentId(UUID incidentId, TenantId tenantId) {
                return Optional.empty();
            }
        };
    }
}
