package com.securedoc.platform.infrastructure.identity;

import com.securedoc.platform.application.port.out.ActiveOidcMachineTenantBindings;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Read-only candidate lookup, requiring ACTIVE identity and enabled PUSH_API source. */
@Repository
public class JdbcActiveOidcMachineTenantBindings implements ActiveOidcMachineTenantBindings {
    private final JdbcTemplate jdbc;

    public JdbcActiveOidcMachineTenantBindings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<TenantId> findEnabledPushTenants(String providerId, URI issuer, String audience,
                                               String clientPrincipal) {
        if (providerId == null || providerId.isBlank() || issuer == null
                || audience == null || audience.isBlank()
                || clientPrincipal == null || clientPrincipal.isBlank()) {
            return Set.of();
        }
        String sql = """
                select distinct c.tenant_id
                from enterprise_identity_connections c
                join tenants t on t.id = c.tenant_id
                join integration_connections i on i.tenant_id = c.tenant_id
                where c.provider_id = ? and c.issuer_uri = ? and c.api_audience = ?
                  and c.status = 'ACTIVE' and t.status = 'ACTIVE'
                  and i.enabled = true and i.purpose = 'INGESTION_SOURCE'
                  and i.provider_type = 'PUSH_API' and i.client_principal = ?
                """;
        Set<TenantId> results = new LinkedHashSet<>();
        jdbc.query(sql, rs -> results.add(TenantId.of(rs.getObject("tenant_id", UUID.class))),
                providerId.trim(), issuer.toString(), audience.trim(), clientPrincipal.trim());
        return Set.copyOf(results);
    }
}
