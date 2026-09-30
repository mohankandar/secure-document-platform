package com.documentplatform.identity;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class IdentityClaims {
    private IdentityClaims() {}

    public static AuthenticatedIdentity normalize(
            String providerId,
            String issuer,
            String subject,
            Map<String, Object> claims,
            IdentityClaimConfig config) {
        if (config == null) config = new IdentityClaimConfig(null, null, null, null);
        Map<String, Object> safeClaims = claims == null ? Map.of() : claims;
        String username = text(safeClaims.get(config.usernameClaim()));
        if (username == null) username = subject;
        return new AuthenticatedIdentity(
                providerId,
                issuer,
                subject,
                username,
                text(safeClaims.get(config.emailClaim())),
                text(safeClaims.get(config.displayNameClaim())),
                values(safeClaims.get(config.groupsClaim())),
                safeClaims);
    }

    public static Set<String> values(Object raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw instanceof Collection<?> collection) {
            if (collection.size() > 200) throw new IllegalArgumentException("groups claim contains too many values");
            for (Object item : collection) add(out, item);
        } else if (raw != null) {
            for (String item : raw.toString().split("[,\\s]+")) add(out, item);
        }
        return Set.copyOf(out);
    }

    private static void add(Set<String> out, Object value) {
        if (value == null) return;
        String normalized = value.toString().trim();
        if (normalized.isEmpty()) return;
        if (normalized.length() > 200) throw new IllegalArgumentException("identity claim value is too long");
        out.add(normalized);
    }

    private static String text(Object raw) {
        if (!(raw instanceof String value)) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
