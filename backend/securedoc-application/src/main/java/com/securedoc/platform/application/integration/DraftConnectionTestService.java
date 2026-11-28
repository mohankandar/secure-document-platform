package com.securedoc.platform.application.integration;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.intake.polling.PollingSourceException;
import com.securedoc.platform.application.integration.IntegrationConnectionService.ConnectionInput;
import com.securedoc.platform.application.port.out.DerivedDestinationDeliveryPort;
import com.securedoc.platform.application.port.out.PollingSourcePort;
import com.securedoc.platform.application.port.out.DocumentStoreConnectionTestPort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.IntegrationConnection;
import com.securedoc.platform.domain.integration.IntegrationPurpose;
import com.securedoc.platform.domain.integration.IntegrationProviderType;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Tests a connection's settings before they are saved (owner's U008 answer 1).
 *
 * <ul>
 *   <li>The settings go through exactly the same checks as Save ({@link IntegrationConnectionService#draft}).</li>
 *   <li>Saves nothing and leaves the stored connection's health as it was.</li>
 *   <li>With a connection id, an empty key reference reuses the stored key, only for a connection of the caller's
 *       own tenant (active entity).</li>
 *   <li>Cloud metadata addresses are always refused ({@link OutboundAddressGuard}).</li>
 *   <li>The answer is only "works" or a fixed reason code: never the remote system's body, headers or any secret.</li>
 *   <li>One audit line, marked draft, without the address or the key reference.</li>
 *   <li>Uses the same safe probes as the saved-connection tests: no customer document is sent.</li>
 * </ul>
 */
public final class DraftConnectionTestService {

    public static final String ADDRESS_NOT_ALLOWED = "ADDRESS_NOT_ALLOWED";
    public static final String ADDRESS_NOT_RESOLVED = "ADDRESS_NOT_RESOLVED";
    public static final String DOCUMENT_STORE_TEST_UNAVAILABLE = "DOCUMENT_STORE_TEST_UNAVAILABLE";
    public static final String CONNECTION_TEST_FAILED = "CONNECTION_TEST_FAILED";
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");

    public record Result(boolean works, String reasonCode) {
        static Result ok() { return new Result(true, null); }
        static Result failed(String code) { return new Result(false, code); }
    }

    private final IntegrationConnectionService connections;
    private final DocumentStoreConnectionTestPort documentStorePort;            // null when document store delivery is not configured
    private final PollingSourcePort pollingPort;
    private final DerivedDestinationDeliveryPort derivedPort;
    private final OutboundAddressGuard addressGuard;
    private final AuditService audit;
    private final Clock clock;

    public DraftConnectionTestService(IntegrationConnectionService connections, DocumentStoreConnectionTestPort documentStorePortOrNull,
                                      PollingSourcePort pollingPort, DerivedDestinationDeliveryPort derivedPort,
                                      OutboundAddressGuard addressGuard, AuditService audit) {
        this(connections, documentStorePortOrNull, pollingPort, derivedPort, addressGuard, audit, Clock.systemUTC());
    }

    DraftConnectionTestService(IntegrationConnectionService connections, DocumentStoreConnectionTestPort documentStorePortOrNull,
                               PollingSourcePort pollingPort, DerivedDestinationDeliveryPort derivedPort,
                               OutboundAddressGuard addressGuard, AuditService audit, Clock clock) {
        this.connections = Objects.requireNonNull(connections);
        this.documentStorePort = documentStorePortOrNull;
        this.pollingPort = Objects.requireNonNull(pollingPort);
        this.derivedPort = Objects.requireNonNull(derivedPort);
        this.addressGuard = Objects.requireNonNull(addressGuard);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    public Result test(TenantId tenantId, IntegrationConnectionId connectionIdOrNull, ActorId actorId,
                       CorrelationId correlationId, ConnectionInput input) {
        // Same checks as Save; NotFound for a connection outside the caller's tenant; ValidationException as Save.
        IntegrationConnection draft = connections.draft(tenantId, connectionIdOrNull, input);
        Kind kind = kindOf(draft);
        if (kind == null) throw new ValidationException(LvErrorCode.CFG_003, "This connection has nothing to test: the other system sends to LexaVault");
        IntegrationConnection probe;
        try {
            // The probes test a switched-on connection. Switching on only adds Save's switch-on checks (e.g. a key is needed).
            probe = draft.enabled() ? draft : draft.setEnabled(true, Instant.now(clock));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(LvErrorCode.CFG_003, e.getMessage());
        }

        Result result = run(kind, probe);
        String target = connectionIdOrNull == null ? "draft" : connectionIdOrNull.toString();
        String detail = "draft=true,purpose=" + probe.purpose() + ",provider=" + probe.providerType()
                + (result.works() ? ",result=works" : ",category=" + result.reasonCode());
        if (!result.works()) {
            // Task 0025: a failed test records its code first (a refused address is LV-CFG-003, the rest LV-CFG-004).
            com.securedoc.platform.domain.error.LvErrorCode code = com.securedoc.platform.application.error.LvErrorCodes.forDetail(result.reasonCode())
                    .filter(c -> c == com.securedoc.platform.domain.error.LvErrorCode.CFG_003).orElse(com.securedoc.platform.domain.error.LvErrorCode.CFG_004);
            detail = com.securedoc.platform.application.error.LvErrorCodes.auditDetail(code, result.reasonCode(), detail);
        }
        if (result.works()) {
            audit.recordSuccess(tenantId, actorId, AuditAction.INTEGRATION_CONNECTION_TESTED, "IntegrationConnection",
                    target, correlationId, detail);
        } else {
            audit.recordFailure(tenantId, actorId, AuditAction.INTEGRATION_CONNECTION_TESTED, "IntegrationConnection",
                    target, correlationId, detail);
        }
        return result;
    }

    private Result run(Kind kind, IntegrationConnection probe) {
        if (probe.providerType() == IntegrationProviderType.HTTP_API) {
            // Task 0110 (hardening: outbound addresses limited): the same rule as Save and the connections.
            var refused = OutboundAddressRule.check(probe.endpoint(), probe.allowedHosts(), addressGuard.ruleResolver());
            if (refused.isPresent() && refused.get() != OutboundAddressRule.Refusal.INVALID) {
                return Result.failed(refused.get() == OutboundAddressRule.Refusal.NOT_RESOLVED ? ADDRESS_NOT_RESOLVED
                        : ADDRESS_NOT_ALLOWED);
            }
            switch (addressGuard.check(probe.endpoint())) {
                case METADATA_ADDRESS: return Result.failed(ADDRESS_NOT_ALLOWED);
                case UNRESOLVED: return Result.failed(ADDRESS_NOT_RESOLVED);
                case INVALID: return Result.failed(kind == Kind.POLLING ? "POLL_CONFIGURATION_ERROR" : "DELIVERY_DESTINATION_INVALID");
                case ALLOWED: break;
            }
        }
        try {
            switch (kind) {
                case DOCUMENT_STORE -> {
                    if (documentStorePort == null) return Result.failed(DOCUMENT_STORE_TEST_UNAVAILABLE);
                    documentStorePort.testConnection(probe);
                }
                case POLLING -> pollingPort.discoverAvailable(probe, 1);  // the list is discarded; nothing is collected
                case DERIVED -> derivedPort.testConnection(probe);
            }
            return Result.ok();
        } catch (RuntimeException e) {
            return Result.failed(reasonCode(e));
        }
    }

    /** Only a fixed code from LexaVault's own vocabulary; never a message, which might carry remote text. */
    static String reasonCode(RuntimeException e) {
        String code = e instanceof TechnicalDependencyException tde ? tde.failureCode().name()
                : e instanceof PollingSourceException pse ? pse.category() : null;
        return code != null && SAFE_CODE.matcher(code).matches() ? code : CONNECTION_TEST_FAILED;
    }

    private enum Kind { DOCUMENT_STORE, POLLING, DERIVED }

    private static Kind kindOf(IntegrationConnection c) {
        if (c.purpose() == IntegrationPurpose.DOCUMENT_STORE_DELIVERY && c.providerType() == IntegrationProviderType.HTTP_API) return Kind.DOCUMENT_STORE;
        if (c.purpose() == IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY) return Kind.DERIVED;
        if (c.pollingSource()) return Kind.POLLING;
        return null;
    }
}
