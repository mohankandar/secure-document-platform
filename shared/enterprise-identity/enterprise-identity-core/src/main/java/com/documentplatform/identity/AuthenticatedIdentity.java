package com.documentplatform.identity;

import java.util.Map;
import java.util.Set;

/** Product-neutral identity created only after an external provider has authenticated a token/user. */
public record AuthenticatedIdentity(
        String providerId,
        String issuer,
        String subject,
        String username,
        String email,
        String displayName,
        Set<String> groups,
        Map<String, Object> claims) {
    public AuthenticatedIdentity {
        providerId = required(providerId, "providerId");
        issuer = required(issuer, "issuer");
        subject = required(subject, "subject");
        username = required(username, "username");
        email = optional(email);
        displayName = optional(displayName);
        groups = groups == null ? Set.of() : Set.copyOf(groups);
        claims = claims == null ? Map.of() : Map.copyOf(claims);
    }
    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
