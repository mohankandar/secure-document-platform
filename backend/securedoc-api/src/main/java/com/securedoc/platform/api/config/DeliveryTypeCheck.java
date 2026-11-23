package com.securedoc.platform.api.config;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.util.Locale;
import java.util.Set;

/**
 * Task 0045: {@code securedoc.delivery.type} names the one final-delivery provider, and it is one of {@link #KNOWN}.
 * Anything else (an old value from before the rename, a typo) stops the start here with one plain sentence, before
 * any bean is made, instead of later as a missing delivery bean. Fail closed: no guessing what was meant.
 */
public final class DeliveryTypeCheck implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    public static final String PROPERTY = "securedoc.delivery.type";
    public static final Set<String> KNOWN = Set.of("document-store", "local");

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        check(context.getEnvironment());
    }

    static void check(Environment environment) {
        String raw = environment.getProperty(PROPERTY);
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (KNOWN.contains(value)) return;
        // The value is a setting, not a secret; it is only repeated when it is plainly a word, so a log line can't be forged.
        String shown = value.matches("[a-z0-9-]{1,40}") ? "'" + value + "'" : "this value";
        throw new IllegalStateException(PROPERTY + " must be 'document-store' or 'local', not " + shown
                + ". Set SECUREDOC_DELIVERY_TYPE to one of those and start again.");
    }
}
