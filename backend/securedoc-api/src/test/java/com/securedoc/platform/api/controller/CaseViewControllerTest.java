package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.caseview.CaseViewService;
import com.securedoc.platform.application.caseview.CaseViewSettings;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.port.out.CaseViewStore;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Task 0108: the View settings are always the signed-in person's, in the entity they are in, and never cached. */
class CaseViewControllerTest {

    @Test
    void readsAndSavesForTheSignedInPersonOnly() throws Exception {
        Map<String, CaseViewSettings> rows = new HashMap<>();
        CaseViewStore store = new CaseViewStore() {
            public Optional<CaseViewSettings> find(TenantId t, ActorId a) { return Optional.ofNullable(rows.get(t + "/" + a)); }
            public void save(TenantId t, ActorId a, CaseViewSettings s) { rows.put(t + "/" + a, s); }
        };
        TenantId tenant = TenantId.generate();
        ActorId me = ActorId.generate();
        RequestContextResolver context = mock(RequestContextResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(context.resolvePrincipal(request)).thenReturn(AuthenticatedPrincipal.of(me, tenant, "person",
                Set.of("DOCUMENT_PROCESSOR")));
        CaseViewController controller = new CaseViewController(context, new CaseViewService(store, t -> List.of()));

        var saved = controller.save(new CaseViewSettings("priority", "desc", List.of("source"), "compact"), request);
        assertEquals(new CaseViewSettings("priority", "desc", List.of("source"), "compact"), saved.getBody().settings());
        assertEquals(Set.of(tenant + "/" + me), rows.keySet());
        var read = controller.read(request);
        assertEquals(saved.getBody().settings(), read.getBody().settings());
        assertTrue(read.getHeaders().getCacheControl().contains("no-store"));

        for (String method : List.of("read", "save")) {
            var found = java.util.Arrays.stream(CaseViewController.class.getMethods())
                    .filter(m -> m.getName().equals(method)).findFirst().orElseThrow();
            assertEquals(UserCapability.DASHBOARD_ACCESS, found.getAnnotation(RequireCapability.class).value()[0]);
        }
    }
}
