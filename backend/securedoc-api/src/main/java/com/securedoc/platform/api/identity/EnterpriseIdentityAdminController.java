package com.securedoc.platform.api.identity;

import com.documentplatform.identity.store.LoginConnection;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.infrastructure.identity.PlatformIdentityAdminReads;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin, Sign-in: read-only (task 0041). Sign-in connections are set through setup ({@code idn_login_connection});
 * this screen shows the ACTIVE LexaVault connection of the entity's customer and changes nothing. The R1 draft, test,
 * activate and rollback endpoints are gone: they wrote {@code enterprise_identity_connections}, which sign-in no longer
 * reads (task 0037), so a change there never changed sign-in. Task 0054: a test sign-in of the ACTIVE connection, which
 * stores and changes nothing.
 *
 * <p>The response keeps the R1 shape so the screens read it as before: {@code staged} is null, there are no rollback
 * candidates and {@code activationSupported} is false. The secret reference is never sent.
 */
@RestController
@RequestMapping("/api/v1/admin/enterprise-identity")
public class EnterpriseIdentityAdminController {

    /** The words both screens show; also in the response so a client can show them as they are. */
    public static final String MANAGED_BY_SETUP = "Sign-in connections are set through setup";

    private final RequestContextResolver context;
    private final PlatformIdentityAdminReads reads;
    private final SignInConnectionTest tester;
    private final AuditService audit;

    public EnterpriseIdentityAdminController(RequestContextResolver context, PlatformIdentityAdminReads reads,
                                             SignInConnectionTest tester, AuditService audit) {
        this.context = context;
        this.reads = reads;
        this.tester = tester;
        this.audit = audit;
    }

    @GetMapping
    @RequireCapability(UserCapability.TENANT_AUTH_MANAGE)
    public ResponseEntity<Overview> overview(HttpServletRequest request) {
        UUID entity = context.resolvePrincipal(request).tenantId().value();
        ConnectionView active = reads.activeConnection(entity).map(c -> ConnectionView.of(entity, c)).orElse(null);
        return ResponseEntity.ok(new Overview(active, null, List.of(), false, 0, true, MANAGED_BY_SETUP));
    }

    /**
     * Tests the ACTIVE connection (task 0054): the login system's discovery document and keys only. It takes no input, so
     * the only address called is the stored connection's; nothing is stored or changed. Audited with codes only.
     */
    @PostMapping("/test-sign-in")
    @RequireCapability(UserCapability.TENANT_AUTH_MANAGE)
    public ResponseEntity<TestAnswer> testSignIn(HttpServletRequest request) {
        var principal = context.resolvePrincipal(request);
        LoginConnection active = reads.activeConnection(principal.tenantId().value()).orElse(null);
        if (active == null) {
            return ResponseEntity.ok(new TestAnswer(false, "NO_ACTIVE_CONNECTION",
                    "No sign-in connection is stored for this entity.", null, null, null));
        }
        SignInConnectionTest.Result result = tester.run(active);
        var correlation = context.resolveCorrelationId(request);
        if (result.passed()) {
            audit.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.ENTERPRISE_IDENTITY_CONNECTION_TESTED,
                    "EnterpriseIdentityConnection", active.connectionId().toString(), correlation, "result=PASSED");
        } else {
            audit.recordFailure(principal.tenantId(), principal.actorId(), AuditAction.ENTERPRISE_IDENTITY_CONNECTION_TESTED,
                    "EnterpriseIdentityConnection", active.connectionId().toString(), correlation,
                    "result=FAILED, code=" + result.code() + (result.reason() == null ? "" : ", reason=" + result.reason()));
        }
        return ResponseEntity.ok(new TestAnswer(result.passed(), result.code(), result.message(), result.reason(),
                result.testedAt(), result.whatToDo()));
    }

    /** What the screen shows of a test: passed, or the exact code and its words. */
    public record TestAnswer(boolean passed, String code, String message, String reason, Instant testedAt,
                             String whatToDo) {
    }

    /** One login connection as the screens show it. {@code displayName} is the provider ID (idn_ keeps no other name). */
    public record ConnectionView(UUID connectionId, UUID tenantId, String providerId, String providerType,
                                 String displayName, String issuerUri, String browserClientId, String apiAudience,
                                 String status, long configurationVersion) {
        static ConnectionView of(UUID entity, LoginConnection c) {
            return new ConnectionView(c.connectionId(), entity, c.providerId(), c.providerType().name(), c.providerId(),
                    c.issuer().toString(), c.browserClientId(), c.apiAudience(), c.status().name(),
                    c.configurationVersion());
        }
    }

    public record Overview(ConnectionView active, ConnectionView staged, List<ConnectionView> rollbackCandidates,
                           boolean activationSupported, long activationMaxTestAgeSeconds, boolean readOnly,
                           String managedBy) {
    }
}
