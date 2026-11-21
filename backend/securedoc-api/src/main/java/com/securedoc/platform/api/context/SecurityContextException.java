package com.securedoc.platform.api.context;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;

/**
 * Thrown when the configured authentication trust boundary cannot resolve a safe application principal.
 * Mapped to a safe fail-closed response by the global exception handler.
 *
 * <p>Task 0023 C5: every refusal carries a {@link #reasonCode() reason code} (a fixed upper-case word, never a value
 * from the request or the token), which is logged and audited. When the refusal happens after LexaVault has already
 * established, from trusted configuration or a trusted connection, which entity and which person it is about, the
 * exception also carries them, so the refusal can be audited in that entity's log. The message is for developers
 * only and is never sent or logged: it may name a header value.
 */
public class SecurityContextException extends RuntimeException {

    /** For refusals created without a reason; nothing in LexaVault should produce it any more. */
    public static final String UNSPECIFIED = "UNSPECIFIED";

    private final String reasonCode;
    private final TenantId tenantId;
    private final ActorId actorId;

    public SecurityContextException(String message) {
        this(message, UNSPECIFIED);
    }

    public SecurityContextException(String message, String reasonCode) {
        this(message, reasonCode, null, null, null);
    }

    private SecurityContextException(String message, String reasonCode, TenantId tenantId, ActorId actorId,
                                     Throwable cause) {
        super(message, cause);
        this.reasonCode = reasonCode == null || reasonCode.isBlank() ? UNSPECIFIED : reasonCode;
        this.tenantId = tenantId;
        this.actorId = actorId;
    }

    /** The same refusal, attributed to an entity and a person LexaVault already trusts for this request. */
    public SecurityContextException attributedTo(TenantId tenant, ActorId actor) {
        return new SecurityContextException(getMessage(), reasonCode, tenant, actor, this);
    }

    public String reasonCode() {
        return reasonCode;
    }

    /** The trusted entity the refusal is about, or null when it happened before any entity was established. */
    public TenantId tenantId() {
        return tenantId;
    }

    /** The trusted person the refusal is about, or null. */
    public ActorId actorId() {
        return actorId;
    }
}
