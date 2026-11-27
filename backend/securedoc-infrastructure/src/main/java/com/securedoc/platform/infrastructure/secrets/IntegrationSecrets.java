package com.securedoc.platform.infrastructure.secrets;

import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;
import com.securedoc.platform.application.port.out.SecretReferenceRule;
import com.securedoc.platform.domain.integration.IntegrationConnection;

import java.util.Objects;
import java.util.Optional;

/**
 * Hardening: secret references limited to approved names. The one reader of a connection's secret for the adapters that
 * sign in to a source or a destination themselves: the app's rule first ({@link SecretReferenceRule}), then the app's own
 * resolver. A reference the rule refuses is never looked up. Errors name the field only, never the reference or a value.
 */
public final class IntegrationSecrets {

    public enum Problem { MISSING, REFUSED, UNAVAILABLE }

    /** Why no secret was given; the message names the field only. */
    public static final class NotRead extends RuntimeException {
        private final Problem problem;

        NotRead(Problem problem, String message) {
            super(message, null, false, false);
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    /** Reads nothing: what an adapter without the app's rule and resolver does. */
    public static final IntegrationSecrets NONE =
            new IntegrationSecrets(SecretReferenceRule.REFUSE_ALL, ref -> Optional.empty());

    private final SecretReferenceRule rule;
    private final SecretResolver resolver;

    public IntegrationSecrets(SecretReferenceRule rule, SecretResolver resolver) {
        this.rule = Objects.requireNonNull(rule);
        this.resolver = Objects.requireNonNull(resolver);
    }

    /** @throws NotRead when the reference is missing, refused or has no value */
    public String read(IntegrationConnection connection) {
        String reference = connection.secretReference();
        if (reference == null || reference.isBlank()) {
            throw new NotRead(Problem.MISSING, "secretReference: no secret is named");
        }
        try {
            rule.check(connection.tenantId(), reference);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new NotRead(Problem.REFUSED, SecretReferenceRule.REFUSED);
        }
        return resolver.resolve(SecretRef.of(reference)).map(Secret::reveal).filter(v -> !v.isBlank())
                .orElseThrow(() -> new NotRead(Problem.UNAVAILABLE, "secretReference: the named secret has no value here"));
    }
}
