package com.securedoc.platform.api.identity;

import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.signin.TestSignIn;
import com.documentplatform.identity.store.ConnectionOutcome;
import com.documentplatform.identity.store.ConnectionValues;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.LoginConnectionStore;
import com.documentplatform.identity.store.LoginConnections;
import com.securedoc.platform.api.security.SecurityMode;
import com.securedoc.platform.api.security.SecurityProperties;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.infrastructure.identity.LexaVaultProduct;
import com.securedoc.platform.infrastructure.identity.PlatformIdentityAdminReads;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Task 0058: the IT Admin's login connection: a draft, its test sign-in, switching to it and going back, over
 * platform-identity's {@link LoginConnections}. The draft is always the one of the caller's own customer and of LexaVault;
 * no connection id is taken from the browser. LexaVault's own rules on top:
 * <ul>
 *   <li><b>Nobody is locked out.</b> This server takes tokens of one login system only, the one in its settings
 *       ({@code securedoc.security.oidc.*}), and admits a person only when the active connection is that one. A draft (or a
 *       previous connection) with another provider ID, issuer or audience is therefore not activated here: it would refuse
 *       everybody at once, the IT Admin included, who could then not go back. It is prepared and tested here, and switched
 *       with the settings and setup.</li>
 *   <li>The secret reference is never shown, sent or changed here; a draft keeps the one it was copied from.</li>
 *   <li>One test sign-in at a time. It reads the draft's issuer and its keys only over the platform's outbound rules
 *       ({@link OutboundRulesConfig}); an answer holds codes and fixed words, never what the other side sent.</li>
 * </ul>
 * Every step is recorded with its code, refused or not. A pasted token is checked and forgotten.
 */
@Service
public class LoginConnectionService {

    static final String SETTINGS_DIFFER = "SIGN_IN_SETTINGS_DIFFER";
    static final String TEST_RUNNING = "TEST_ALREADY_RUNNING";
    static final String NO_DRAFT = "NO_DRAFT";
    /** A signed token is a few kilobytes; anything much larger is not one. */
    static final int MAX_TOKEN = 32 * 1024;

    /** One step's answer. {@code reason} is the outbound rule's code when a test's call was refused. */
    public record Answer(boolean done, String code, String message, String whatToDo, String reason) {
    }

    private final LoginConnections connections;
    private final LoginConnectionStore store;
    private final PlatformIdentityAdminReads reads;
    private final SecurityProperties security;
    private final AuditService audit;
    private final ReentrantLock testing = new ReentrantLock();

    public LoginConnectionService(LoginConnections connections, LoginConnectionStore store, PlatformIdentityAdminReads reads,
                                  SecurityProperties security, AuditService audit) {
        this.connections = Objects.requireNonNull(connections);
        this.store = Objects.requireNonNull(store);
        this.reads = Objects.requireNonNull(reads);
        this.security = Objects.requireNonNull(security);
        this.audit = Objects.requireNonNull(audit);
    }

    /** The customer of the caller's entity; empty when the entity is not a slot of this installation. */
    private Optional<String> customer(AuthenticatedPrincipal principal) {
        return reads.customerOf(principal.tenantId().value());
    }

    public Optional<LoginConnection> active(AuthenticatedPrincipal principal) {
        return customer(principal).flatMap(c -> connections.active(c, LexaVaultProduct.CODE));
    }

    public Optional<LoginConnection> draft(AuthenticatedPrincipal principal) {
        return customer(principal).flatMap(c -> connections.pending(c, LexaVaultProduct.CODE));
    }

    /** What going back would make active again. */
    public Optional<LoginConnection> previous(AuthenticatedPrincipal principal) {
        return customer(principal).flatMap(c -> store.rollbackCandidate(c, LexaVaultProduct.CODE));
    }

    /** One line of the history: which version, its status, where the change came from, who and when. */
    public record HistoryLine(String issuerUri, long version, String status, String source, String changedBy,
                              java.time.Instant changedAt) {
    }

    /** The newest changes of the customer's LexaVault connections, newest first. */
    public List<HistoryLine> history(AuthenticatedPrincipal principal, int limit) {
        return customer(principal).map(c -> connections.connections(c, LexaVaultProduct.CODE).stream()
                .flatMap(connection -> store.history(connection.connectionId()).stream().map(h -> new HistoryLine(
                        connection.issuer().toString(), h.configurationVersion(), h.status().name(), h.source().name(),
                        h.changedBy(), h.changedAt())))
                .sorted(java.util.Comparator.comparing(HistoryLine::changedAt).reversed()).limit(limit).toList())
                .orElse(List.of());
    }

    public Answer startDraft(AuthenticatedPrincipal principal, CorrelationId correlation, long expectedActiveVersion) {
        return customer(principal).map(c -> finish(principal, correlation, AuditAction.ENTERPRISE_IDENTITY_DRAFT_SAVED,
                connections.createDraft(c, LexaVaultProduct.CODE, expectedActiveVersion, null, actor(principal))))
                .orElseGet(() -> unknownCustomer(principal, correlation, AuditAction.ENTERPRISE_IDENTITY_DRAFT_SAVED));
    }

    public Answer saveDraft(AuthenticatedPrincipal principal, CorrelationId correlation, long expectedVersion,
                            String providerId, String issuer, String browserClientId, String audience) {
        return onDraft(principal, correlation, AuditAction.ENTERPRISE_IDENTITY_DRAFT_SAVED, draft -> {
            ConnectionValues values;
            try {
                values = new ConnectionValues(providerId, IdentityProviderType.OIDC, URI.create(issuer == null ? "" : issuer.strip()),
                        browserClientId == null || browserClientId.isBlank() ? null : browserClientId, audience,
                        draft.secretReference());
            } catch (IllegalArgumentException notAnAddress) {
                return refuse(principal, correlation, AuditAction.ENTERPRISE_IDENTITY_DRAFT_SAVED, draft,
                        ConnectionOutcome.Result.IDN_VALUES_INVALID.code(),
                        ConnectionOutcome.Result.IDN_VALUES_INVALID.userMessage(),
                        ConnectionOutcome.Result.IDN_VALUES_INVALID.fix());
            }
            return finish(principal, correlation, AuditAction.ENTERPRISE_IDENTITY_DRAFT_SAVED,
                    connections.updateDraft(draft.connectionId(), expectedVersion, values, actor(principal)));
        });
    }

    public Answer discardDraft(AuthenticatedPrincipal principal, CorrelationId correlation, long expectedVersion) {
        return onDraft(principal, correlation, AuditAction.ENTERPRISE_IDENTITY_DRAFT_SAVED, draft -> finish(principal,
                correlation, AuditAction.ENTERPRISE_IDENTITY_DRAFT_SAVED,
                connections.discardDraft(draft.connectionId(), expectedVersion, actor(principal))));
    }

    /** @param token a signed token to check too, or null; never stored, recorded or given back */
    public Answer testDraft(AuthenticatedPrincipal principal, CorrelationId correlation, long expectedVersion, String token) {
        AuditAction action = AuditAction.ENTERPRISE_IDENTITY_CONNECTION_TESTED;
        return onDraft(principal, correlation, action, draft -> {
            if (token != null && token.length() > MAX_TOKEN) {
                return refuse(principal, correlation, action, draft, "TOKEN_TOO_LONG", "The pasted text is not a signed token.",
                        "Paste one token, or leave it empty to check the login system's address and keys only.");
            }
            if (!testing.tryLock()) {
                return refuse(principal, correlation, action, draft, TEST_RUNNING, "A test sign-in is already running.",
                        "Wait for it to end, then try again.");
            }
            try {
                return finish(principal, correlation, action, connections.validate(draft.connectionId(), expectedVersion,
                        new TestSignIn(token, security.getOidc().getRolesClaim()), actor(principal)));
            } finally {
                testing.unlock();
            }
        });
    }

    public Answer activate(AuthenticatedPrincipal principal, CorrelationId correlation, long expectedVersion) {
        return onDraft(principal, correlation, AuditAction.ENTERPRISE_IDENTITY_CONNECTION_ACTIVATION_FAILED, draft -> {
            if (!asThisServerSignsIn(draft)) {
                return settingsDiffer(principal, correlation, AuditAction.ENTERPRISE_IDENTITY_CONNECTION_ACTIVATION_FAILED, draft);
            }
            ConnectionOutcome outcome = connections.activate(draft.connectionId(), expectedVersion, actor(principal));
            return finish(principal, correlation, outcome.refused()
                    ? AuditAction.ENTERPRISE_IDENTITY_CONNECTION_ACTIVATION_FAILED
                    : AuditAction.ENTERPRISE_IDENTITY_CONNECTION_ACTIVATED, outcome);
        });
    }

    /** @param expectedActiveVersion the active connection's version the person saw: going back is never done on an old screen */
    public Answer rollBack(AuthenticatedPrincipal principal, CorrelationId correlation, long expectedActiveVersion) {
        AuditAction failed = AuditAction.ENTERPRISE_IDENTITY_CONNECTION_ROLLBACK_FAILED;
        Optional<String> customer = customer(principal);
        if (customer.isEmpty()) return unknownCustomer(principal, correlation, failed);
        Optional<LoginConnection> active = connections.active(customer.get(), LexaVaultProduct.CODE);
        if (active.map(LoginConnection::configurationVersion).orElse(0L) != expectedActiveVersion) {
            ConnectionOutcome.Result conflict = ConnectionOutcome.Result.IDN_VERSION_CONFLICT;
            return refuse(principal, correlation, failed, active.orElse(null), conflict.code(), conflict.userMessage(), conflict.fix());
        }
        Optional<LoginConnection> previous = store.rollbackCandidate(customer.get(), LexaVaultProduct.CODE);
        if (previous.isPresent() && !asThisServerSignsIn(previous.get())) {
            return settingsDiffer(principal, correlation, failed, previous.get());
        }
        ConnectionOutcome outcome = connections.rollback(customer.get(), LexaVaultProduct.CODE, actor(principal));
        return finish(principal, correlation, outcome.refused() ? failed
                : AuditAction.ENTERPRISE_IDENTITY_CONNECTION_ROLLED_BACK, outcome);
    }

    /** Whether people who sign in to this server as it is set now would be admitted through this connection. */
    boolean asThisServerSignsIn(LoginConnection connection) {
        if (security.getMode() != SecurityMode.OIDC) return true;
        var oidc = security.getOidc();
        return connection.providerId().equals(oidc.getProviderId().trim())
                && connection.issuer().toString().equals(oidc.getIssuerUri())
                && connection.apiAudience().equals(oidc.getAudience().trim());
    }

    private Answer settingsDiffer(AuthenticatedPrincipal principal, CorrelationId correlation, AuditAction action,
                                  LoginConnection connection) {
        return refuse(principal, correlation, action, connection, SETTINGS_DIFFER,
                "This server signs people in with another login system, provider ID or audience. Switching here would refuse everybody.",
                "Change the server's sign-in settings and run setup with --replace-connection at the same restart, with the values tested here.");
    }

    private Answer onDraft(AuthenticatedPrincipal principal, CorrelationId correlation, AuditAction action,
                           java.util.function.Function<LoginConnection, Answer> work) {
        return draft(principal).map(work).orElseGet(() -> refuse(principal, correlation, action, null, NO_DRAFT,
                "There is no draft.", "Start a draft first."));
    }

    private Answer unknownCustomer(AuthenticatedPrincipal principal, CorrelationId correlation, AuditAction action) {
        ConnectionOutcome.Result unknown = ConnectionOutcome.Result.IDN_CUSTOMER_UNKNOWN;
        return refuse(principal, correlation, action, null, unknown.code(), unknown.userMessage(), unknown.fix());
    }

    private Answer refuse(AuthenticatedPrincipal principal, CorrelationId correlation, AuditAction action,
                          LoginConnection connection, String code, String message, String whatToDo) {
        write(principal, correlation, action, connection, true, "code=" + code);
        return new Answer(false, code, message, whatToDo, null);
    }

    private Answer finish(AuthenticatedPrincipal principal, CorrelationId correlation, AuditAction action,
                          ConnectionOutcome outcome) {
        // A failed test's own code (IDN_TEST_AUDIENCE, ...) says more than "the test failed".
        var code = outcome.result() == ConnectionOutcome.Result.IDN_TEST_FAILED && outcome.test() != null
                && outcome.test().failureCode().isPresent() ? outcome.test().failureCode().get()
                : (com.documentplatform.errors.PlatformCode) outcome.result();
        String reason = outcome.test() == null ? null : outcome.test().reason();
        write(principal, correlation, action, outcome.connection(), outcome.refused(), "code=" + code.code()
                + (reason == null ? "" : ", reason=" + reason)
                + (outcome.test() == null ? "" : ", tokenChecked=" + outcome.test().tokenChecked())
                + (outcome.connection() == null ? "" : ", version=" + outcome.connection().configurationVersion()));
        return new Answer(!outcome.refused(), code.code(), code.userMessage(), outcome.refused() ? code.fix() : null, reason);
    }

    private void write(AuthenticatedPrincipal principal, CorrelationId correlation, AuditAction action,
                       LoginConnection connection, boolean failed, String detail) {
        String id = connection == null ? "none" : connection.connectionId().toString();
        if (failed) {
            audit.recordFailure(principal.tenantId(), principal.actorId(), action, "EnterpriseIdentityConnection", id, correlation, detail);
        } else {
            audit.recordSuccess(principal.tenantId(), principal.actorId(), action, "EnterpriseIdentityConnection", id, correlation, detail);
        }
    }

    private static String actor(AuthenticatedPrincipal principal) {
        return principal.actorId().value().toString();
    }
}
