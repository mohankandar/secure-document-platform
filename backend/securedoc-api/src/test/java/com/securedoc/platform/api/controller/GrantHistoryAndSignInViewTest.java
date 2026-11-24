package com.securedoc.platform.api.controller;

import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.GrantChange;
import com.documentplatform.identity.store.LoginConnection;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.identity.EnterpriseIdentityAdminController;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.identity.PlatformIdentityAdminReads;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 0041: the Audit page's grant history and the read-only Sign-in screen, without a database. */
class GrantHistoryAndSignInViewTest {

    private static final UUID ENTITY = UUID.fromString("30216241-b724-5a3a-aef3-3034fd546d17");
    private static final Instant NOW = Instant.parse("2026-11-22T18:00:00Z");
    private static final URI ISSUER = URI.create("http://localhost:8181/realms/document-platform-local");

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final PlatformIdentityAdminReads reads = mock(PlatformIdentityAdminReads.class);
    private final RequestContextResolver context = mock(RequestContextResolver.class);

    GrantHistoryAndSignInViewTest() {
        when(context.resolvePrincipal(request)).thenReturn(AuthenticatedPrincipal.of(
                ActorId.of(UUID.randomUUID()), TenantId.of(ENTITY), "auditor", Set.of("BUSINESS_ADMIN")));
    }

    private static GrantChange change(int minutesAgo, String group, String before, String after, GrantChange.Kind kind,
                                      ChangeSource source) {
        return new GrantChange("TEN-DEMO-0001", "LEXAVAULT", "ENT-DEMO-0001", ENTITY, ISSUER, group, before, after, kind,
                source, source == ChangeSource.SETUP ? "platform-setup" : "app",
                NOW.minus(Duration.ofMinutes(minutesAgo)), source == ChangeSource.SETUP ? "run-1" : null);
    }

    private GrantHistoryController history() {
        return new GrantHistoryController(context, reads, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void theHistoryIsTheEntitysNewestFirstForTheLastSevenDaysByDefault() {
        when(reads.grantHistory(eq(ENTITY), any(), any())).thenReturn(List.of(
                change(30, "BusinessUsers", null, "DOCUMENT_PROCESSOR", GrantChange.Kind.ADDED, ChangeSource.SETUP),
                change(10, "BusinessUsers", "DOCUMENT_PROCESSOR", "DOCUMENT_SUPERVISOR", GrantChange.Kind.CHANGED, ChangeSource.APP)));
        List<GrantHistoryController.Row> rows = history().list(null, null, request).getBody();
        assertEquals(2, rows.size());
        assertEquals("CHANGED", rows.get(0).change());
        assertEquals("DOCUMENT_SUPERVISOR", rows.get(0).roleAfter());
        assertNull(rows.get(0).setupRunId());
        assertEquals("ADDED", rows.get(1).change());
        assertEquals("SETUP", rows.get(1).source());
        assertEquals("run-1", rows.get(1).setupRunId());
        verify(reads).grantHistory(ENTITY, NOW.plusSeconds(1).minus(Duration.ofDays(7)), NOW.plusSeconds(1));
    }

    @Test
    void theRangeIsCheckedAndTheListIsCapped() {
        assertThrows(ValidationException.class, () -> history().list("yesterday", null, request));
        assertThrows(ValidationException.class, () -> history().list("2026-11-22T10:00:00Z", "2026-11-21T10:00:00Z", request));
        List<GrantChange> many = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            many.add(change(500 - i, "G" + i, null, "REPORT_VIEWER", GrantChange.Kind.ADDED, ChangeSource.SETUP));
        }
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-30T00:00:00Z");
        when(reads.grantHistory(ENTITY, from, to)).thenReturn(many);
        List<GrantHistoryController.Row> rows = history().list(from.toString(), to.toString(), request).getBody();
        assertEquals(GrantHistoryController.MAX_ROWS, rows.size());
        assertEquals("G249", rows.get(0).group(), "the newest first");
    }

    @Test
    void theHistoryNeedsAuditView() throws Exception {
        RequireCapability required = GrantHistoryController.class
                .getMethod("list", String.class, String.class, HttpServletRequest.class).getAnnotation(RequireCapability.class);
        assertTrue(List.of(required.value()).contains(UserCapability.AUDIT_VIEW));
    }

    @Test
    void theSignInScreenShowsTheActiveConnectionReadOnlyAndNeverItsSecretReference() throws Exception {
        UUID connectionId = UUID.randomUUID();
        when(reads.activeConnection(ENTITY)).thenReturn(Optional.of(new LoginConnection(connectionId, "TEN-DEMO-0001",
                "LEXAVAULT", "customer-oidc", IdentityProviderType.OIDC, ISSUER, "lexavault-web", "lexavault-api",
                "vault:secret/never-shown", IdentityConnectionStatus.ACTIVE, 3)));
        var overview = new EnterpriseIdentityAdminController(context, reads, null, null).overview(request).getBody();
        assertEquals(ISSUER.toString(), overview.active().issuerUri());
        assertEquals("customer-oidc", overview.active().displayName());
        assertEquals("ACTIVE", overview.active().status());
        assertEquals(3, overview.active().configurationVersion());
        assertNull(overview.staged());
        assertTrue(overview.rollbackCandidates().isEmpty());
        assertFalse(overview.activationSupported());
        assertTrue(overview.readOnly());
        assertEquals("Sign-in connections are set through setup", overview.managedBy());
        String json = JsonMapper.builderWithJackson2Defaults().build().writeValueAsString(overview);
        assertFalse(json.contains("never-shown"), json);
        assertFalse(json.toLowerCase().contains("secret"), json);

        when(reads.activeConnection(ENTITY)).thenReturn(Optional.empty());
        assertNull(new EnterpriseIdentityAdminController(context, reads, null, null).overview(request).getBody().active());
    }
}
