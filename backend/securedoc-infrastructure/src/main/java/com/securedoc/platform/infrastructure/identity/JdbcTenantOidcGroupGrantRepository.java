package com.securedoc.platform.infrastructure.identity;

import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.admission.EntityGroupGrants;
import com.documentplatform.identity.admission.EntityId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.application.port.out.TenantOidcGroupGrantRepository;
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
public class JdbcTenantOidcGroupGrantRepository implements TenantOidcGroupGrantRepository, EntityGroupGrants {
    private final JdbcTemplate jdbc;

    public JdbcTenantOidcGroupGrantRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
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
            if (rawRole == null || !Set.of("DOCUMENT_PROCESSOR", "DOCUMENT_SUPERVISOR",
                    "BUSINESS_ADMIN", "REPORT_VIEWER", "TENANT_IT_ADMIN").contains(rawRole)) {
                throw new IllegalStateException("Invalid or platform-level tenant group role configuration");
            }
            groups.computeIfAbsent(tenant, k -> new LinkedHashMap<>())
                    .computeIfAbsent(externalGroup, k -> new LinkedHashSet<>()).add(rawRole);
        }, providerId.trim(), issuer.toString(), audience.trim());
        Map<TenantId, Map<String, Set<String>>> immutable = new LinkedHashMap<>();
        groups.forEach((tenant, mapping) -> {
            Map<String, Set<String>> perTenant = new LinkedHashMap<>();
            mapping.forEach((group, roles) -> perTenant.put(group, Set.copyOf(roles)));
            immutable.put(tenant, Map.copyOf(perTenant));
        });
        return Map.copyOf(immutable);
    }

    /**
     * Enterprise Identity 0.3.0 seam: the same lookup, SQL and role-name check as the method above, in the library's
     * types. A mistyped role in any grant row still refuses the whole lookup, as before.
     */
    @Override
    public Map<EntityId, Map<String, Set<String>>> activeGrants(ConnectionKey key) {
        Map<TenantId, Map<String, Set<String>>> grants = activeGrants(key.providerId(), key.issuer(), key.audience());
        Map<EntityId, Map<String, Set<String>>> converted = new LinkedHashMap<>();
        grants.forEach((tenant, mapping) -> converted.put(EntityId.of(tenant.value()), mapping));
        return Map.copyOf(converted);
    }
}
