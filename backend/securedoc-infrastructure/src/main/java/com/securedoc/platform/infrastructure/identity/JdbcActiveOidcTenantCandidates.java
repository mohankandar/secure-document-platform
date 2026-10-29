package com.securedoc.platform.infrastructure.identity;

import com.securedoc.platform.application.port.out.ActiveOidcTenantCandidates;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Discovers ACTIVE connection candidates without inferring user membership.
 * Compatible with the current unique issuer binding and with a future
 * shared-issuer migration; always returns candidates, not an authorized tenant.
 */
@Repository
public class JdbcActiveOidcTenantCandidates implements ActiveOidcTenantCandidates {
    private final JdbcTemplate jdbc;

    public JdbcActiveOidcTenantCandidates(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Set<TenantId> findCandidates(String providerId, URI issuer, String audience) {
        if (providerId == null || providerId.isBlank()
                || issuer == null || audience == null || audience.isBlank()) {
            return Set.of();
        }
        String sql = """
                select distinct c.tenant_id
                from enterprise_identity_connections c
                join tenants t on t.id = c.tenant_id
                where c.provider_id = ? and c.issuer_uri = ? and c.api_audience = ?
                  and c.status = 'ACTIVE' and t.status = 'ACTIVE'
                """;
        Set<TenantId> candidates = new LinkedHashSet<>();
        jdbc.query(sql, rs -> candidates.add(
                TenantId.of(rs.getObject("tenant_id", UUID.class))),
                providerId.trim(), issuer.toString(), audience.trim());
        return Set.copyOf(candidates);
    }
}
