package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.security.SecurityContextRefusalRecorder;
import com.securedoc.platform.api.security.SecurityMode;
import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.tenant.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OidcTenantDiscoveryControllerTest {
    @Test
    void localSecurityModeCannotEnumerateTenantMemberships() {
        RequestContextResolver resolver = mock(RequestContextResolver.class);
        TenantRepository repository = mock(TenantRepository.class);
        when(resolver.securityMode()).thenReturn(SecurityMode.LOCAL);

        OidcTenantDiscoveryController controller = new OidcTenantDiscoveryController(resolver, repository);
        assertThrows(SecurityContextException.class, () -> controller.list(null));
        verify(resolver, never()).resolveAvailableOidcTenants();
        verifyNoInteractions(repository);
    }

    @Test
    void anAdmittedEntityLeftOutOfTheChoicesIsAuditedWithItsReason() {
        // Task 0023 C5 (#236): before, the entity just disappeared from the list without a trace.
        Tenant active = Tenant.create("Active", "active-entity");
        Tenant suspended = Tenant.create("Suspended", "suspended-entity");
        active.activate();
        suspended.activate();
        suspended.suspend();
        TenantId missing = TenantId.generate();
        ActorId actor = ActorId.generate();

        RequestContextResolver resolver = mock(RequestContextResolver.class);
        when(resolver.securityMode()).thenReturn(SecurityMode.OIDC);
        Map<TenantId, Set<String>> memberships = new LinkedHashMap<>();
        memberships.put(active.id(), Set.of("DOCUMENT_PROCESSOR"));
        memberships.put(suspended.id(), Set.of("DOCUMENT_PROCESSOR"));
        memberships.put(missing, Set.of("DOCUMENT_PROCESSOR"));
        when(resolver.resolveAvailableOidcTenants()).thenReturn(memberships);
        when(resolver.currentOidcActor()).thenReturn(Optional.of(actor));
        TenantRepository tenants = mock(TenantRepository.class);
        when(tenants.findById(active.id())).thenReturn(Optional.of(active));
        when(tenants.findById(suspended.id())).thenReturn(Optional.of(suspended));
        when(tenants.findById(missing)).thenReturn(Optional.empty());
        List<AuditRecord> audits = new ArrayList<>();
        var controller = new OidcTenantDiscoveryController(resolver, tenants,
                new SecurityContextRefusalRecorder(new AuditService(audits::add)));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me/tenants");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/me/tenants");

        var choices = controller.list(request).getBody();

        assertEquals(List.of(active.id().toString()), choices.stream().map(c -> c.tenantId()).toList());
        assertEquals(2, audits.size());
        assertTrue(audits.stream().allMatch(a -> a.action() == AuditAction.SECURITY_CONTEXT_INVALID
                && a.actorId().equals(actor)));
        assertEquals("code=LV-AUT-005, detail=TENANT_NOT_ACTIVE, method=GET, route=/api/v1/me/tenants, reason=TENANT_NOT_ACTIVE", audits.stream()
                .filter(a -> a.tenantId().equals(suspended.id())).findFirst().orElseThrow().detail());
        assertEquals("code=LV-AUT-005, detail=TENANT_MISSING, method=GET, route=/api/v1/me/tenants, reason=TENANT_MISSING", audits.stream()
                .filter(a -> a.tenantId().equals(missing)).findFirst().orElseThrow().detail());
    }

    /** Task 0058: the remembered entity marks one of the allowed choices; it never adds one. */
    @Test
    void theEntityWorkedInLastIsMarkedAndNeverAddedWhenItIsNoLongerAllowed() {
        Tenant one = Tenant.create("One", "one");
        Tenant two = Tenant.create("Two", "two");
        one.activate();
        two.activate();
        ActorId actor = ActorId.generate();
        RequestContextResolver resolver = mock(RequestContextResolver.class);
        when(resolver.securityMode()).thenReturn(SecurityMode.OIDC);
        when(resolver.currentOidcActor()).thenReturn(Optional.of(actor));
        when(resolver.resolveAvailableOidcTenants()).thenReturn(Map.of(one.id(), Set.of("DOCUMENT_PROCESSOR"),
                two.id(), Set.of("BUSINESS_ADMIN")));
        TenantRepository tenants = mock(TenantRepository.class);
        when(tenants.findById(one.id())).thenReturn(Optional.of(one));
        when(tenants.findById(two.id())).thenReturn(Optional.of(two));
        var last = mock(com.securedoc.platform.infrastructure.identity.LastEntityStore.class);
        var controller = new OidcTenantDiscoveryController(resolver, tenants, null, last);

        when(last.find(actor)).thenReturn(Optional.of(two.id()));
        assertEquals(List.of(two.id().toString()), controller.list(null).getBody().stream().filter(c -> c.lastUsed())
                .map(c -> c.tenantId()).toList());

        when(last.find(actor)).thenReturn(Optional.of(TenantId.generate()));
        var choices = controller.list(null).getBody();
        assertEquals(2, choices.size());
        assertTrue(choices.stream().noneMatch(c -> c.lastUsed()));

        when(last.find(actor)).thenReturn(Optional.empty());
        assertTrue(controller.list(null).getBody().stream().noneMatch(c -> c.lastUsed()));
    }
}
