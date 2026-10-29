package com.securedoc.platform.infrastructure.identity;

import com.securedoc.platform.api.security.ProductRole;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.util.*;

/**
 * Read-only, fail-closed tenant OIDC group-policy lookup.
 * Only ACTIVE enterprise identity connections can supply tenant grants.
 * Does not create users, credentials, or memberships in a LexaVault user directory.
 */
@Repository
public class JdbcTenantOidcGroupGrantRepository {
    private final JdbcTemplate jdbc;

    public JdbcTenantOidcGroupGrantRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public Map<TenantId, Map<String, Set<String>>> activeGrants(
            String providerId, URI issuer, String audience) {
        Objects.requireNonNull(issuer, "issuer");
        if (providerId == null || providerId.isBlank() || audience == null || audience.isBlank()) {
            return Map.of();
        }
        String sql = """
                select g.tenant_id, g.external_group, g.product_role
                from tenant_oidc_group_grants g
                join enterprise_identity_connections c
                  on c.tenant_id = g.tenant_id and c.issuer_uri = g.issuer_uri
                join tenants t on t.id = g.tenant_id
                where c.provider_id = ? and c.issuer_uri = ? and c.api_audience = ?
                  and c.status = 'ACTIVE' and t.status = 'ACTIVE'
                """;
        Map<TenantId, Map<String, Set<String>>> groups = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            TenantId tenant = TenantId.of(rs.getObject("tenant_id", UUID.class));
            String externalGroup = rs.getString("external_group");
            String rawRole = rs.getString("product_role");
            ProductRole role = ProductRole.fromIdentityName(rawRole)
                    .orElseThrow(() -> new IllegalStateException("Invalid tenant group role configuration"));
            if (role == ProductRole.PLATFORM_OPERATIONS_ADMIN) {
                throw new IllegalStateException("Platform Operations cannot be assigned by tenant groups");
            }
            groups.computeIfAbsent(tenant, k -> new LinkedHashMap<>())
                    .computeIfAbsent(externalGroup, k -> new LinkedHashSet<>()).add(role.name());
        }, providerId.trim(), issuer.toString(), audience.trim());
        Map<TenantId, Map<String, Set<String>>> immutable = new LinkedHashMap<>();
        groups.forEach((tenant, mapping) -> {
            Map<String, Set<String>> perTenant = new LinkedHashMap<>();
            mapping.forEach((group, roles) -> perTenant.put(group, Set.copyOf(roles)));
            immutable.put(tenant, Map.copyOf(perTenant));
        });
        return Map.copyOf(immutable);
    }
}
