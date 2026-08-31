package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.security.SecurityProperties;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class OidcRoleMapper {
    public static final Set<String> PRODUCT_ROLES = Set.of(
            "USER", "REVIEWER", "APPROVER", "BUSINESS_ADMIN", "PLATFORM_ADMIN", "ADMIN");

    private final SecurityProperties properties;

    public OidcRoleMapper(SecurityProperties properties) {
        this.properties = properties;
    }

    public Set<String> map(Object externalClaim) {
        Set<String> externalValues = normalizeExternalValues(externalClaim);
        Set<String> mapped = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : properties.getOidc().getRoleMappings().entrySet()) {
            String productRole = entry.getKey().trim().toUpperCase(Locale.ROOT);
            if (!PRODUCT_ROLES.contains(productRole)) {
                continue;
            }
            for (String configuredExternal : split(entry.getValue())) {
                if (externalValues.contains(configuredExternal)) {
                    mapped.add(productRole);
                    break;
                }
            }
        }
        if (mapped.isEmpty()) {
            throw new SecurityContextException("Authenticated identity has no mapped application role");
        }
        return Set.copyOf(mapped);
    }

    private Set<String> normalizeExternalValues(Object raw) {
        Set<String> values = new LinkedHashSet<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null && !item.toString().isBlank()) values.add(item.toString().trim());
            }
        } else if (raw != null) {
            for (String item : split(raw.toString())) values.add(item);
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
