package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.api.security.ProductRole;
import com.securedoc.platform.domain.identity.TenantId;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * Converts trusted IdP group memberships to tenant-scoped product roles.
 * All mappings are supplied by the server-side configuration store, never by the request.
 */
public final class TenantGroupAuthorization {
    public Set<String> authorizedRoles(TenantId requestedTenant, Object signedGroups,
                                       Map<TenantId, Map<String, Set<String>>> mappings) {
        Objects.requireNonNull(requestedTenant, "requestedTenant");
        if (mappings == null || !mappings.containsKey(requestedTenant)) {
            throw new SecurityContextException("Tenant has no trusted group admission policy");
        }
        Map<String, Set<String>> grants = mappings.get(requestedTenant);
        if (grants == null || grants.isEmpty()) {
            throw new SecurityContextException("Tenant has no trusted group admission policy");
        }
        Set<String> external = new LinkedHashSet<>();
        if (signedGroups instanceof Collection<?> groups) {
            for (Object group : groups) {
                if (group instanceof String value && !value.isBlank()) external.add(value.trim());
            }
        } else {
            throw new SecurityContextException("Trusted IdP group collection is required");
        }
        Set<String> authorized = new LinkedHashSet<>();
        for (String group : external) {
            Set<String> roles = grants.get(group);
            if (roles != null) {
                for (String role : roles) {
                    ProductRole canonical = ProductRole.fromIdentityName(role)
                            .orElseThrow(() -> new SecurityContextException("Invalid tenant role configuration"));
                    if (canonical == ProductRole.PLATFORM_OPERATIONS_ADMIN) {
                        throw new SecurityContextException("Platform Operations cannot be granted through tenant IdP groups");
                    }
                    authorized.add(canonical.name());
                }
            }
        }
        if (authorized.isEmpty()) {
            throw new SecurityContextException("Authenticated identity has no tenant-authorized role");
        }
        return Set.copyOf(authorized);
    }
}
