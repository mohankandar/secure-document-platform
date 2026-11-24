package com.securedoc.platform.api.identity;

import com.documentplatform.identity.store.LoginConnection;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.identity.LoginConnectionService.Answer;
import com.securedoc.platform.api.identity.LoginConnectionService.HistoryLine;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.infrastructure.identity.PlatformIdentityAdminReads;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin, Sign-in (IT Admin). The ACTIVE LexaVault connection of the entity's customer ({@code idn_login_connection}), a test
 * sign-in of it (task 0054), and since task 0058 a draft, its test sign-in, switching to it and going back
 * ({@link LoginConnectionService}; before, the screen only showed the connection). No id of a connection is taken; the draft's
 * own values and a pasted test token are all the browser sends, and the secret reference is never sent.
 */
@RestController
@RequestMapping("/api/v1/admin/enterprise-identity")
public class EnterpriseIdentityAdminController {

    static final int HISTORY = 20;

    private final RequestContextResolver context;
    private final PlatformIdentityAdminReads reads;
    private final SignInConnectionTest tester;
    private final AuditService audit;
    private final LoginConnectionService service;

    public EnterpriseIdentityAdminController(RequestContextResolver context, PlatformIdentityAdminReads reads,
                                             SignInConnectionTest tester, AuditService audit, LoginConnectionService service) {
        this.context = context;
        this.reads = reads;
        this.tester = tester;
        this.audit = audit;
        this.service = service;
    }

    @GetMapping
    @RequireCapability(UserCapability.TENANT_AUTH_MANAGE)
    public ResponseEntity<Overview> overview(HttpServletRequest request) {
        AuthenticatedPrincipal principal = context.resolvePrincipal(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Overview(
                view(service.active(principal).orElse(null)), view(service.draft(principal).orElse(null)),
                view(service.previous(principal).orElse(null)), service.history(principal, HISTORY)));
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

    /** Starts a draft: a copy of the active connection, at the version the person saw. */
    @PostMapping("/draft")
    @RequireCapability(UserCapability.TENANT_AUTH_MANAGE)
    public ResponseEntity<Answer> startDraft(@RequestBody VersionRequest body, HttpServletRequest request) {
        return ResponseEntity.ok(service.startDraft(context.resolvePrincipal(request), context.resolveCorrelationId(request),
                body.expectedVersion()));
    }

    @PutMapping("/draft")
    @RequireCapability(UserCapability.TENANT_AUTH_MANAGE)
    public ResponseEntity<Answer> saveDraft(@RequestBody DraftRequest body, HttpServletRequest request) {
        return ResponseEntity.ok(service.saveDraft(context.resolvePrincipal(request), context.resolveCorrelationId(request),
                body.expectedVersion(), body.providerId(), body.issuerUri(), body.browserClientId(), body.apiAudience()));
    }

    @PostMapping("/draft/discard")
    @RequireCapability(UserCapability.TENANT_AUTH_MANAGE)
    public ResponseEntity<Answer> discardDraft(@RequestBody VersionRequest body, HttpServletRequest request) {
        return ResponseEntity.ok(service.discardDraft(context.resolvePrincipal(request), context.resolveCorrelationId(request),
                body.expectedVersion()));
    }

    @PostMapping("/draft/test-sign-in")
    @RequireCapability(UserCapability.TENANT_AUTH_MANAGE)
    public ResponseEntity<Answer> testDraft(@RequestBody DraftTestRequest body, HttpServletRequest request) {
        return ResponseEntity.ok(service.testDraft(context.resolvePrincipal(request), context.resolveCorrelationId(request),
                body.expectedVersion(), body.token()));
    }

    @PostMapping("/draft/activate")
    @RequireCapability(UserCapability.TENANT_AUTH_MANAGE)
    public ResponseEntity<Answer> activateDraft(@RequestBody VersionRequest body, HttpServletRequest request) {
        return ResponseEntity.ok(service.activate(context.resolvePrincipal(request), context.resolveCorrelationId(request),
                body.expectedVersion()));
    }

    /** Goes back to the previous active connection, from the active version the person saw. */
    @PostMapping("/rollback")
    @RequireCapability(UserCapability.TENANT_AUTH_MANAGE)
    public ResponseEntity<Answer> rollBack(@RequestBody VersionRequest body, HttpServletRequest request) {
        return ResponseEntity.ok(service.rollBack(context.resolvePrincipal(request), context.resolveCorrelationId(request),
                body.expectedVersion()));
    }

    public record VersionRequest(long expectedVersion) {
    }

    public record DraftRequest(long expectedVersion, String providerId, String issuerUri, String browserClientId,
                               String apiAudience) {
    }

    public record DraftTestRequest(long expectedVersion, String token) {
        /** The token is never part of a text. */
        @Override
        public String toString() {
            return "DraftTestRequest[expectedVersion=" + expectedVersion + "]";
        }
    }

    /** What the screen shows of a test: passed, or the exact code and its words. */
    public record TestAnswer(boolean passed, String code, String message, String reason, Instant testedAt,
                             String whatToDo) {
    }

    /** A login connection as the screen shows it, and whether this server can switch to it as it is set now. */
    public record ConnectionView(UUID connectionId, String providerId, String providerType, String displayName,
                                 String issuerUri, String browserClientId, String apiAudience, String status,
                                 long configurationVersion, boolean matchesSettings) {
    }

    private ConnectionView view(LoginConnection c) {
        return c == null ? null : new ConnectionView(c.connectionId(), c.providerId(), c.providerType().name(), c.providerId(),
                c.issuer().toString(), c.browserClientId(), c.apiAudience(), c.status().name(), c.configurationVersion(),
                service.asThisServerSignsIn(c));
    }

    public record Overview(ConnectionView active, ConnectionView draft, ConnectionView previous, List<HistoryLine> history) {
    }
}
