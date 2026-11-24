package com.securedoc.platform.api.identity;

import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.inmemory.InMemoryIdentityStore;
import com.documentplatform.identity.signin.IssuerFetch;
import com.documentplatform.identity.signin.OidcTestSignIn;
import com.documentplatform.identity.spring.OutboundIssuerFetch;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.LoginConnections;
import com.documentplatform.identity.testkit.MockIssuer;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.Outbound;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.identity.LoginConnectionService.Answer;
import com.securedoc.platform.api.security.SecurityMode;
import com.securedoc.platform.api.security.SecurityProperties;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditOutcome;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.identity.PlatformIdentityAdminReads;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Task 0058 step 4: drafts, their test sign-in, switching and going back, on the testkit's mock login server and the real outbound rules. */
class LoginConnectionServiceTest {

    private static final String CUSTOMER = "TEN-CONN-0001";
    private static final UUID ENTITY = UUID.fromString("30216241-b724-5a3a-aef3-3034fd546d17");
    private static final CorrelationId CORRELATION = CorrelationId.generate();
    private static final String SECRET = "vault:kv/customers/never-shown#client_secret";

    private final MockIssuer issuer = MockIssuer.start();
    private final InMemoryIdentityStore store = new InMemoryIdentityStore();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final SecurityProperties security = new SecurityProperties();
    private final PlatformIdentityAdminReads reads = mock(PlatformIdentityAdminReads.class);
    private final AuthenticatedPrincipal admin = AuthenticatedPrincipal.of(ActorId.of(UUID.randomUUID()), TenantId.of(ENTITY),
            "it.admin", Set.of("TENANT_IT_ADMIN"));
    private LoginConnectionService service;
    private boolean developmentLicence = true;

    @BeforeEach
    void setUp() {
        when(reads.customerOf(ENTITY)).thenReturn(Optional.of(CUSTOMER));
        store.saveCustomer(CUSTOMER, "Customer");
        store.insert(connection(CUSTOMER, IdentityConnectionStatus.ACTIVE), ChangeSource.SETUP, "setup");
        security.setMode(SecurityMode.OIDC);
        security.getOidc().setIssuerUri(issuer.issuer().toString());
        security.getOidc().setAudience("lexavault-api");
        IssuerFetch fetch = new OutboundIssuerFetch(new Outbound(Installation.onPremises()));
        LoginConnections connections = new LoginConnections(store, new OidcTestSignIn(fetch, Clock.systemUTC()), () -> developmentLicence);
        service = new LoginConnectionService(connections, store, reads, security, new AuditService(audits::add));
    }

    @AfterEach
    void stop() {
        issuer.close();
    }

    private LoginConnection connection(String customer, IdentityConnectionStatus status) {
        return new LoginConnection(UUID.randomUUID(), customer, "LEXAVAULT", "customer-oidc", IdentityProviderType.OIDC,
                issuer.issuer(), "lexavault-web", "lexavault-api", SECRET, status, 1);
    }

    private LoginConnection draft() {
        return service.draft(admin).orElseThrow();
    }

    private Answer save(String issuerUri, String browserClientId) {
        return service.saveDraft(admin, CORRELATION, draft().configurationVersion(), "customer-oidc", issuerUri, browserClientId, "lexavault-api");
    }

    private String lastDetail() {
        return audits.get(audits.size() - 1).detail();
    }

    @Test
    void aDraftIsTestedSwitchedToAndGoneBackFromWhileTheActiveOneKeepsWorking() {
        assertTrue(service.startDraft(admin, CORRELATION, 1).done());
        assertEquals(IdentityConnectionStatus.ACTIVE, service.active(admin).orElseThrow().status());
        assertTrue(save(issuer.issuer().toString(), "lexavault-web-2").done());
        assertEquals(SECRET, draft().secretReference(), "the reference is kept and never asked for");
        // Not tested yet: not switched to.
        assertEquals("IDN_NOT_VALIDATED", service.activate(admin, CORRELATION, draft().configurationVersion()).code());
        String token = issuer.token("lexavault-api", List.of("Finance Staff"), Duration.ofMinutes(5));
        Answer tested = service.testDraft(admin, CORRELATION, draft().configurationVersion(), token);
        assertTrue(tested.done(), tested.toString());
        assertTrue(lastDetail().contains("code=VALIDATED") && lastDetail().contains("tokenChecked=true"), lastDetail());
        // An edit after the test: to be tested again.
        assertTrue(save(issuer.issuer().toString(), "lexavault-web-3").done());
        assertEquals("IDN_NOT_VALIDATED", service.activate(admin, CORRELATION, draft().configurationVersion()).code());
        assertTrue(service.testDraft(admin, CORRELATION, draft().configurationVersion(), null).done());
        assertTrue(service.activate(admin, CORRELATION, draft().configurationVersion()).done());
        assertEquals("lexavault-web-3", service.active(admin).orElseThrow().browserClientId());
        assertTrue(service.draft(admin).isEmpty());
        assertEquals("lexavault-web", service.previous(admin).orElseThrow().browserClientId());

        long seen = service.active(admin).orElseThrow().configurationVersion();
        assertEquals("IDN_VERSION_CONFLICT", service.rollBack(admin, CORRELATION, seen + 1).code(), "not from an old screen");
        assertTrue(service.rollBack(admin, CORRELATION, seen).done());
        assertEquals("lexavault-web", service.active(admin).orElseThrow().browserClientId());
        assertEquals(AuditAction.ENTERPRISE_IDENTITY_CONNECTION_ROLLED_BACK, audits.get(audits.size() - 1).action());

        // Who changed what is in platform-identity's own history; no record holds the token or the secret reference.
        assertTrue(service.history(admin, 50).stream().anyMatch(h -> h.changedBy().equals(admin.actorId().value().toString())));
        for (AuditRecord record : audits) {
            assertFalse(record.detail().contains(token) || record.detail().contains("never-shown")
                    || record.detail().contains(issuer.issuer().getHost()), record.detail());
        }
    }

    /** This server signs people in with one login system; switching to another would refuse everybody, with no way back. */
    @Test
    void aConnectionThisServerDoesNotSignInWithIsTestedButNeverSwitchedTo() {
        for (String what : List.of("issuer", "provider", "audience")) {
            try (MockIssuer other = MockIssuer.start()) {
                assertTrue(service.startDraft(admin, CORRELATION, service.active(admin).orElseThrow().configurationVersion()).done());
                Answer saved = service.saveDraft(admin, CORRELATION, draft().configurationVersion(),
                        what.equals("provider") ? "other-oidc" : "customer-oidc",
                        (what.equals("issuer") ? other.issuer() : issuer.issuer()).toString(), "lexavault-web",
                        what.equals("audience") ? "other-api" : "lexavault-api");
                assertTrue(saved.done(), saved.toString());
                assertTrue(service.testDraft(admin, CORRELATION, draft().configurationVersion(), null).done(), what);
                Answer refused = service.activate(admin, CORRELATION, draft().configurationVersion());
                assertEquals("SIGN_IN_SETTINGS_DIFFER", refused.code(), what);
                assertFalse(refused.whatToDo().isBlank());
                assertEquals(issuer.issuer(), service.active(admin).orElseThrow().issuer());
                assertEquals(AuditAction.ENTERPRISE_IDENTITY_CONNECTION_ACTIVATION_FAILED, audits.get(audits.size() - 1).action());
                assertEquals(AuditOutcome.FAILURE, audits.get(audits.size() - 1).outcome());
                assertTrue(service.discardDraft(admin, CORRELATION, draft().configurationVersion()).done());
            }
        }
    }

    /** Going back is held to the same rule: the settings were moved with setup since. */
    @Test
    void goingBackToALoginSystemThisServerNoLongerUsesIsRefused() {
        assertTrue(service.startDraft(admin, CORRELATION, 1).done());
        assertTrue(save(issuer.issuer().toString(), "lexavault-web-2").done());
        assertTrue(service.testDraft(admin, CORRELATION, draft().configurationVersion(), null).done());
        assertTrue(service.activate(admin, CORRELATION, draft().configurationVersion()).done());
        security.getOidc().setAudience("lexavault-api-new");
        assertEquals("SIGN_IN_SETTINGS_DIFFER",
                service.rollBack(admin, CORRELATION, service.active(admin).orElseThrow().configurationVersion()).code());
        assertEquals("lexavault-web-2", service.active(admin).orElseThrow().browserClientId());
    }

    @Test
    void theTestSaysExactlyWhatFailedAndATokenIsNeverInTheAnswerOrTheRecord() {
        assertTrue(service.startDraft(admin, CORRELATION, 1).done());
        long version = draft().configurationVersion();
        String wrongAudience = issuer.token("someone-else", List.of("g"), Duration.ofMinutes(5));
        Answer failed = service.testDraft(admin, CORRELATION, version, wrongAudience);
        assertEquals("IDN_TEST_AUDIENCE", failed.code());
        assertFalse(failed.done());
        assertFalse(failed.toString().contains(wrongAudience) || lastDetail().contains(wrongAudience));
        assertEquals("IDN_TEST_TOKEN_UNREADABLE", service.testDraft(admin, CORRELATION, version, "not a token").code());
        assertEquals("TOKEN_TOO_LONG", service.testDraft(admin, CORRELATION, version, "x".repeat(40_000)).code());
        assertEquals("IDN_TEST_GROUPS_CLAIM_MISSING", service.testDraft(admin, CORRELATION, version,
                issuer.sign(issuer.claims("lexavault-api", Duration.ofMinutes(5)))).code());
        issuer.down();
        assertEquals("IDN_TEST_ISSUER_UNREACHABLE", service.testDraft(admin, CORRELATION, version, null).code());
        assertEquals("DraftTestRequest[expectedVersion=3]",
                new EnterpriseIdentityAdminController.DraftTestRequest(3, "the.secret.token").toString());
    }

    /** A draft's address is typed by a person: only the platform's outbound rules decide what is called. */
    @Test
    void aDraftIsNoWayToCallInternalAddresses() {
        assertTrue(service.startDraft(admin, CORRELATION, 1).done());
        developmentLicence = false;
        for (String address : List.of("http://localhost:8080/realms/x", "http://169.254.169.254/latest", "ftp://login.example",
                "https://user:pw@login.example", "https://login.example/?q=1", "file:///etc/passwd", "", "not an address")) {
            int calls = issuer.calls();
            Answer refused = save(address, "lexavault-web");
            assertFalse(refused.done(), address);
            assertTrue(Set.of("IDN_HTTP_ISSUER_NOT_ALLOWED", "IDN_VALUES_INVALID").contains(refused.code()), refused.toString());
            assertEquals(calls, issuer.calls());
        }
        developmentLicence = true;
        for (String address : List.of("https://127.0.0.1:1/realms/x", "https://localhost:1", "https://10.0.0.1", "https://192.168.1.1",
                "https://169.254.169.254", "https://[::1]:1", "https://[fd00::1]")) {
            assertTrue(save(address, "lexavault-web").done(), address);
            Answer failed = service.testDraft(admin, CORRELATION, draft().configurationVersion(), null);
            assertEquals("IDN_TEST_ISSUER_UNREACHABLE", failed.code(), address);
            assertTrue(failed.reason() != null && failed.reason().startsWith("OUT_"), address + " " + failed);
        }
    }

    @Test
    void anotherCustomersDraftAndAnOldScreenAreOutOfReach() {
        store.saveCustomer("TEN-CONN-0002", "Other");
        store.insert(connection("TEN-CONN-0002", IdentityConnectionStatus.DRAFT), ChangeSource.APP, "other");
        assertTrue(service.draft(admin).isEmpty());
        assertEquals("NO_DRAFT", service.activate(admin, CORRELATION, 1).code());
        assertEquals("NO_DRAFT", service.discardDraft(admin, CORRELATION, 1).code());
        assertEquals("IDN_VERSION_CONFLICT", service.startDraft(admin, CORRELATION, 7).code());
        assertTrue(service.startDraft(admin, CORRELATION, 1).done());
        assertEquals("IDN_DRAFT_EXISTS", service.startDraft(admin, CORRELATION, 1).code());
        assertEquals("IDN_VERSION_CONFLICT", service.discardDraft(admin, CORRELATION, 9).code());
        when(reads.customerOf(ENTITY)).thenReturn(Optional.empty());
        assertEquals("IDN_CUSTOMER_UNKNOWN", service.startDraft(admin, CORRELATION, 1).code());
        assertEquals("NO_DRAFT", service.testDraft(admin, CORRELATION, 1, null).code());
        assertTrue(service.active(admin).isEmpty());
    }

    @Test
    void theScreenShowsTheConnectionTheDraftAndWhatGoingBackGivesButNeverTheSecretReference() throws Exception {
        assertTrue(service.startDraft(admin, CORRELATION, 1).done());
        assertTrue(save(URI.create("https://new.login.example/oauth2").toString(), "lexavault-web").done());
        RequestContextResolver context = mock(RequestContextResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(context.resolvePrincipal(request)).thenReturn(admin);
        var overview = new EnterpriseIdentityAdminController(context, reads, null, null, service).overview(request).getBody();
        assertTrue(overview.active().matchesSettings());
        assertEquals("customer-oidc", overview.active().displayName());
        assertEquals("https://new.login.example/oauth2", overview.draft().issuerUri());
        assertFalse(overview.draft().matchesSettings(), "so the screen says it can't be switched to here");
        assertNull(overview.previous());
        assertFalse(overview.history().isEmpty());
        String json = JsonMapper.builderWithJackson2Defaults().build().writeValueAsString(overview);
        assertFalse(json.contains("never-shown") || json.toLowerCase().contains("secret"), json);
    }
}
