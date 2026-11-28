package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.casepage.CasePageSettings;
import com.securedoc.platform.application.casepage.CasePageSettingsService;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.CasePageSettingsStore;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Task 0108: the Cases-page settings are the signed-in admin's entity's, need the policy right, and are never cached. */
class CasePageSettingsControllerTest {

    @Test
    void readsAndSavesForTheSignedInEntityOnly() throws Exception {
        Map<TenantId, CasePageSettings> rows = new HashMap<>();
        CasePageSettingsStore store = new CasePageSettingsStore() {
            public Optional<CasePageSettings> find(TenantId t) { return Optional.ofNullable(rows.get(t)); }
            public void save(TenantId t, ActorId a, CasePageSettings s) { rows.put(t, s); }
            public OptionalInt shortestExpiryDays() { return OptionalInt.empty(); }
        };
        TenantId tenant = TenantId.generate();
        RequestContextResolver context = mock(RequestContextResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(context.resolvePrincipal(request)).thenReturn(AuthenticatedPrincipal.of(ActorId.generate(), tenant, "admin",
                Set.of("BUSINESS_ADMIN")));
        when(context.resolveCorrelationId(request)).thenReturn(CorrelationId.generate());
        CasePageSettingsController controller = new CasePageSettingsController(context,
                new CasePageSettingsService(store, new AuditService(r -> { }), 7));

        var first = controller.read(request).getBody();
        assertEquals(new CasePageSettingsController.Response(240, 480, 7, 7), first);
        var saved = controller.save(new CasePageSettingsController.Body(60, 90, 3), request);
        assertEquals(new CasePageSettingsController.Response(60, 90, 3, 7), saved.getBody());
        assertEquals(Set.of(tenant), rows.keySet());
        assertTrue(saved.getHeaders().getCacheControl().contains("no-store"));
        assertThrows(ValidationException.class, () -> controller.save(null, request));

        for (String method : List.of("read", "save")) {
            var found = java.util.Arrays.stream(CasePageSettingsController.class.getMethods())
                    .filter(m -> m.getName().equals(method)).findFirst().orElseThrow();
            assertEquals(UserCapability.TENANT_POLICY_MANAGE, found.getAnnotation(RequireCapability.class).value()[0]);
        }
    }
}
