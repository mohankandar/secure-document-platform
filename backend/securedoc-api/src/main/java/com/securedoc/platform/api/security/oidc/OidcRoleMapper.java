package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.security.ProductRole;
import com.securedoc.platform.api.security.SecurityProperties;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Component
public class OidcRoleMapper {
    private final SecurityProperties properties;

    public OidcRoleMapper(SecurityProperties properties) {
        this.properties = properties;
    }

    public Set<String> map(Object externalClaim) {
        Set<String> externalValues = normalizeExternalValues(externalClaim);
        Set<String> mapped = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : properties.getOidc().getRoleMappings().entrySet()) {
            var canonicalRole = ProductRole.fromIdentityName(entry.getKey());
            if (canonicalRole.isEmpty()) continue;
            for (String configuredExternal : split(entry.getValue())) {
                if (externalValues.contains(configuredExternal)) {
                    mapped.add(canonicalRole.get().name());
                    break;
                }
            }
        }
        if (mapped.isEmpty()) {
            throw new SecurityContextException("Authenticated identity has no mapped application role", "NO_MAPPED_ROLE");
        }
        return Set.copyOf(mapped);
    }

    /** Task 0054 (platform-core 0.7.0, SEC-44): exactly as the login system sends them; a claim that is one text is one group. */
    private Set<String> normalizeExternalValues(Object raw) {
        Set<String> values = new LinkedHashSet<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null && !item.toString().isBlank()) values.add(item.toString());
            }
        } else if (raw != null) {
            if (!raw.toString().isBlank()) values.add(raw.toString());
        }
        return values;
    }

    private Set<String> split(String raw) {
        Set<String> values = new LinkedHashSet<>();
        if (raw == null) return values;
        for (String item : raw.split(",")) {
            String normalized = item.trim();
            if (!normalized.isEmpty()) values.add(normalized);
        }
        return values;
    }
}
