package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.TenantId;

/**
 * Hardening: secret references limited to approved names. The one rule for which secret names an entity's settings may
 * use, on save and at each use. The app gives its own rule (the same one as for every other secret it reads); nothing
 * else decides.
 */
@FunctionalInterface
public interface SecretReferenceRule {

    /** The text of every refusal: it names the field, never the reference or a value. */
    String REFUSED = "secretReference: the secret's name is not one this entity may use";

    /** Refuses every reference: what a service without the app's rule does. */
    SecretReferenceRule REFUSE_ALL = (entity, reference) -> {
        throw new IllegalArgumentException(REFUSED);
    };

    /** @throws IllegalArgumentException when this entity may not use the reference; the message never repeats it */
    void check(TenantId entity, String reference);
}
