package com.securedoc.platform.infrastructure.identity;

import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.application.port.out.TenantOidcGroupGrantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.util.*;

/**
 * Read-only, fail-closed tenant OIDC group-policy lookup.
 * Only ACTIVE enterprise identity connections can supply tenant grants.
 * Does not create users, credentials, or memberships in a LexaVault user directory.
 *
 * <p>A bad row (written by hand, not by the role mapping loader) no longer fails the lookup for every entity on the
 * login system. A role written exactly as an entity role, or exactly as the platform role, is passed on as it is;
 * anything else (a typo, lower case, an alias) is passed on marked as unrecognised, so the identity library refuses
 * that entity alone and never reads it as a valid role. Each bad row is logged as an ERROR.
 */
@Repository
public class JdbcTenantOidcGroupGrantRepository implements TenantOidcGroupGrantRepository {
    private static final Logger log = LoggerFactory.getLogger(JdbcTenantOidcGroupGrantRepository.class);
    static final Set<String> ENTITY_ROLES = Set.of("DOCUMENT_PROCESSOR", "DOCUMENT_SUPERVISOR",
            "BUSINESS_ADMIN", "REPORT_VIEWER", "TENANT_IT_ADMIN");
    static final String PLATFORM_ROLE = "PLATFORM_OPERATIONS_ADMIN";
    /** Prefix that no role name can match, so the identity library reports the grant as an unknown role. */
    static final String UNRECOGNISED_PREFIX = "UNRECOGNISED:";

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
            String role = rawRole;
            if (rawRole == null || !ENTITY_ROLES.contains(rawRole)) {
                log.error("Invalid group grant for tenant {}: group \"{}\" has role \"{}\", which is not a tenant role. "
                        + "People matched by this group are refused until the grant is corrected.",
                        tenant, externalGroup, rawRole);
                role = PLATFORM_ROLE.equals(rawRole) ? rawRole : UNRECOGNISED_PREFIX + rawRole;
            }
            groups.computeIfAbsent(tenant, k -> new LinkedHashMap<>())
                    .computeIfAbsent(externalGroup, k -> new LinkedHashSet<>()).add(role);
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
