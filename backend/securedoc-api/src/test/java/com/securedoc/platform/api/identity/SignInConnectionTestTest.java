package com.securedoc.platform.api.identity;

import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.inmemory.InMemoryIdentityStore;
import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.identity.signin.IssuerFetch;
import com.documentplatform.identity.spring.OutboundIssuerFetch;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.testkit.MockIssuer;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.Outbound;
import com.documentplatform.outbound.spring.OutboundProperties;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.identity.PlatformIdentityAdminReads;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Task 0054: the read-only test sign-in, on the identity testkit's mock login server and the real outbound rules. */
class SignInConnectionTestTest {

    private static final UUID ENTITY = UUID.fromString("30216241-b724-5a3a-aef3-3034fd546d17");
    private static final DevelopmentLicence DEVELOPMENT = () -> true;

    private final MockIssuer issuer = MockIssuer.start();
    private final MovingClock clock = new MovingClock();
    private final IssuerFetch fetch = new OutboundIssuerFetch(new Outbound(Installation.onPremises()));

    private static final class MovingClock extends Clock {
        private Instant now = Instant.parse("2026-11-24T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @AfterEach
    void stop() {
        issuer.close();
    }

    private static LoginConnection connection(URI issuer, IdentityProviderType type, long version) {
        return new LoginConnection(UUID.fromString("11111111-1111-4111-8111-111111111111"), "TEN-TEST-0001", "LEXAVAULT",
                "customer-oidc", type, issuer, "lexavault-web", "lexavault-api", null, IdentityConnectionStatus.ACTIVE, version);
    }

    private static LoginConnection connection(URI issuer) {
        return connection(issuer, IdentityProviderType.OIDC, 1);
    }

    private SignInConnectionTest tester(DevelopmentLicence licence) {
        return new SignInConnectionTest(fetch, licence, clock);
    }

    @Test
    void aLoginSystemThatAnswersPasses() {
        SignInConnectionTest.Result result = tester(DEVELOPMENT).run(connection(issuer.issuer()));
        assertTrue(result.passed(), result.toString());
        assertNull(result.code());
        assertEquals(clock.instant(), result.testedAt());
        assertEquals(2, issuer.calls(), "the discovery document and the keys, nothing else");
    }

    @Test
    void eachFailureGivesItsExactCode() {
        issuer.discoveryStatus(404);
        assertFailure("IDN_TEST_ISSUER_UNREACHABLE", "HTTP_404");
        issuer.discoveryStatus(200).discoveryIssuer("https://other.example");
        assertFailure("IDN_TEST_DISCOVERY_MISMATCH", null);
        issuer.discoveryIssuer(issuer.issuer().toString()).keysStatus(500);
        assertFailure("IDN_TEST_KEYS_MISSING", "HTTP_500");
        issuer.keysStatus(200).noKeys();
        assertFailure("IDN_TEST_KEYS_MISSING", null);
        issuer.down();
        SignInConnectionTest.Result down = tester(DEVELOPMENT).run(connection(issuer.issuer()));
        assertEquals("IDN_TEST_ISSUER_UNREACHABLE", down.code());
        assertTrue(down.reason().startsWith("OUT_"), down.reason());
    }

    private void assertFailure(String code, String reason) {
        SignInConnectionTest.Result result = tester(DEVELOPMENT).run(connection(issuer.issuer()));
        assertFalse(result.passed());
        assertEquals(code, result.code());
        assertEquals(reason, result.reason());
        assertFalse(result.message().isBlank());
        assertFalse(result.message().contains(issuer.issuer().toString()), result.message());
    }

    @Test
    void plainHttpIsTestedOnlyOnThisMachineUnderADevelopmentLicence() {
        SignInConnectionTest.Result refused = tester(DevelopmentLicence.NONE).run(connection(issuer.issuer()));
        assertEquals("IDN_HTTP_ISSUER_NOT_ALLOWED", refused.code());
        assertFalse(refused.message().isBlank());
        refused = tester(DEVELOPMENT).run(connection(URI.create("http://login.example.test/realms/x")));
        assertEquals("IDN_HTTP_ISSUER_NOT_ALLOWED", refused.code());
        assertEquals(0, issuer.calls(), "nothing was called");
    }

    @Test
    void theOutboundRulesStopInternalAndMetadataAddresses() {
        for (String address : List.of("https://169.254.169.254/x", "https://127.0.0.1/x", "https://10.0.0.1/x",
                "https://metadata.google.internal/x")) {
            SignInConnectionTest.Result result = tester(DEVELOPMENT).run(connection(URI.create(address)));
            assertEquals("IDN_TEST_ISSUER_UNREACHABLE", result.code(), address);
            assertEquals("OUT_ADDRESS_BLOCKED", result.reason(), address);
        }
    }

    @Test
    void onlyOidcConnectionsAreTested() {
        assertEquals("IDN_VALUES_INVALID", tester(DEVELOPMENT).run(connection(issuer.issuer(), IdentityProviderType.SAML, 1)).code());
        assertEquals(0, issuer.calls());
    }

    @Test
    void manyClicksAreOneCallAndAChangedConnectionIsTestedAgain() throws Exception {
        SignInConnectionTest tester = tester(DEVELOPMENT);
        Thread[] clicks = new Thread[8];
        for (int i = 0; i < clicks.length; i++) {
            clicks[i] = new Thread(() -> tester.run(connection(issuer.issuer())));
            clicks[i].start();
        }
        for (Thread t : clicks) t.join(20_000);
        assertEquals(2, issuer.calls());
        clock.now = clock.now.plus(SignInConnectionTest.KEEP_FOR).minusSeconds(1);
        tester.run(connection(issuer.issuer()));
        assertEquals(2, issuer.calls());
        clock.now = clock.now.plusSeconds(1);
        tester.run(connection(issuer.issuer()));
        assertEquals(4, issuer.calls(), "after ten seconds it is tested again");
        tester.run(connection(issuer.issuer(), IdentityProviderType.OIDC, 2));
        assertEquals(6, issuer.calls(), "another version of the connection is tested at once");
    }

    @Test
    void theScreensTestIsAuditedWithCodesOnlyAndChangesNoConnection() {
        InMemoryIdentityStore store = new InMemoryIdentityStore();
        store.saveCustomer("TEN-TEST-0001", "Customer");
        LoginConnection stored = store.insert(connection(issuer.issuer()), ChangeSource.SETUP, "setup");
        RequestContextResolver context = mock(RequestContextResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        PlatformIdentityAdminReads reads = mock(PlatformIdentityAdminReads.class);
        AuditService audit = mock(AuditService.class);
        ActorId actor = ActorId.of(UUID.randomUUID());
        CorrelationId correlation = CorrelationId.generate();
        when(context.resolvePrincipal(request)).thenReturn(AuthenticatedPrincipal.of(actor, TenantId.of(ENTITY), "it", Set.of("TENANT_IT_ADMIN")));
        when(context.resolveCorrelationId(request)).thenReturn(correlation);
        when(reads.activeConnection(ENTITY)).thenAnswer(i -> store.active("TEN-TEST-0001", "LEXAVAULT"));
        var controller = new EnterpriseIdentityAdminController(context, reads, tester(DEVELOPMENT), audit);

        var passed = controller.testSignIn(request).getBody();
        assertTrue(passed.passed(), passed.toString());
        verify(audit).recordSuccess(TenantId.of(ENTITY), actor, AuditAction.ENTERPRISE_IDENTITY_CONNECTION_TESTED,
                "EnterpriseIdentityConnection", stored.connectionId().toString(), correlation, "result=PASSED");
        clock.now = clock.now.plus(Duration.ofMinutes(1));
        issuer.discoveryStatus(503);
        var failed = controller.testSignIn(request).getBody();
        assertEquals("IDN_TEST_ISSUER_UNREACHABLE", failed.code());
        verify(audit).recordFailure(TenantId.of(ENTITY), actor, AuditAction.ENTERPRISE_IDENTITY_CONNECTION_TESTED,
                "EnterpriseIdentityConnection", stored.connectionId().toString(), correlation,
                "result=FAILED, code=IDN_TEST_ISSUER_UNREACHABLE, reason=HTTP_503");
        // Nothing stored: the same connection, the same version, and no history beyond setup's row.
        assertEquals(List.of(stored), store.connections("TEN-TEST-0001", "LEXAVAULT"));
        assertEquals(1, store.history(stored.connectionId()).size());
    }

    @Test
    void withoutAConnectionThereIsNothingToTest() {
        RequestContextResolver context = mock(RequestContextResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        PlatformIdentityAdminReads reads = mock(PlatformIdentityAdminReads.class);
        AuditService audit = mock(AuditService.class);
        when(context.resolvePrincipal(request)).thenReturn(AuthenticatedPrincipal.of(ActorId.of(UUID.randomUUID()),
                TenantId.of(ENTITY), "it", Set.of("TENANT_IT_ADMIN")));
        when(reads.activeConnection(ENTITY)).thenReturn(Optional.empty());
        var answer = new EnterpriseIdentityAdminController(context, reads, tester(DEVELOPMENT), audit).testSignIn(request).getBody();
        assertFalse(answer.passed());
        assertEquals("NO_ACTIVE_CONNECTION", answer.code());
        verifyNoInteractions(audit);
        assertEquals(0, issuer.calls());
    }

    @Test
    void theOutboundSettingsAreCheckedAndInternalAddressesAreOffByDefault() {
        OutboundProperties p = new OutboundProperties();
        assertInvalid(p, "installation is required");
        p.setInstallation(OutboundProperties.InstallationMode.ON_PREMISES);
        assertFalse(OutboundRulesConfig.installation(p).allowInternalAddresses());
        assertFalse(OutboundRulesConfig.installation(p).allowPlainHttp());
        p.getProxy().setHost("proxy.corp.example");
        assertInvalid(p, "go together");
        p.getProxy().setPort(3128);
        assertEquals("proxy.corp.example", OutboundRulesConfig.installation(p).proxy().host());
        p.getProxy().setUser("svc");
        p.getProxy().setPasswordSecret("outbound/proxy-password");
        assertInvalid(p, "a proxy with sign-in is not supported");
        OutboundProperties hosted = new OutboundProperties();
        hosted.setInstallation(OutboundProperties.InstallationMode.HOSTED);
        hosted.setAllowInternalAddresses(true);
        assertInvalid(hosted, "never allowed on the hosted service");
        OutboundProperties ca = new OutboundProperties();
        ca.setInstallation(OutboundProperties.InstallationMode.ON_PREMISES);
        ca.setPrivateCaFile("relative.pem");
        assertInvalid(ca, "absolute path");
    }

    private static void assertInvalid(OutboundProperties p, String problem) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> OutboundRulesConfig.installation(p));
        assertTrue(e.getMessage().startsWith("OUTBOUND_SETTINGS_INVALID: platform.outbound.") && e.getMessage().contains(problem), e.getMessage());
    }
}
