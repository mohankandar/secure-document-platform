package com.securedoc.platform.infrastructure.identity;

import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.mapping.Grant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** JDBC store for the role mapping loader. Writes rows only; never changes the schema. */
@Repository
public class JdbcRoleMappingStore implements RoleMappingStore {

    private final JdbcTemplate jdbc;

    public JdbcRoleMappingStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Optional<Target> describe(EntityId tenant) {
        List<Target> found = jdbc.query("""
                select t.name, t.status,
                       exists (select 1 from enterprise_identity_connections c
                               where c.tenant_id = t.id and c.status = 'ACTIVE') as active_login
                from tenants t where t.id = ?
                """, (rs, n) -> new Target(rs.getString("name"), "ACTIVE".equals(rs.getString("status")),
                rs.getBoolean("active_login")), tenant.value());
        return found.stream().findFirst();
    }

    @Override
    public void lock(EntityId tenant) {
        jdbc.query("select id from tenants where id = ? for update", rs -> { }, tenant.value());
    }

    @Override
    public Optional<String> activeIssuer(EntityId tenant) {
        List<String> issuers = jdbc.queryForList("""
                select issuer_uri from enterprise_identity_connections
                where tenant_id = ? and status = 'ACTIVE'
                """, String.class, tenant.value());
        // Migration 034 allows one ACTIVE record per tenant; more than one is refused rather than guessed.
        if (issuers.size() > 1) {
            throw new IllegalStateException("Tenant " + tenant + " has more than one ACTIVE login-system record");
        }
        return issuers.stream().findFirst();
    }

    @Override
    public Set<Grant> grants(EntityId tenant, String issuer) {
        return new LinkedHashSet<>(jdbc.query("""
                select external_group, product_role from tenant_oidc_group_grants
                where tenant_id = ? and issuer_uri = ?
                order by external_group, product_role
                """, (rs, n) -> new Grant(rs.getString("external_group"), rs.getString("product_role")),
                tenant.value(), issuer));
    }

    @Override
    public void add(EntityId tenant, String issuer, Grant grant) {
        jdbc.update("""
                insert into tenant_oidc_group_grants (tenant_id, issuer_uri, external_group, product_role)
                values (?, ?, ?, ?)
                """, tenant.value(), issuer, grant.group(), grant.role());
    }

    @Override
    public void remove(EntityId tenant, String issuer, Grant grant) {
        jdbc.update("""
                delete from tenant_oidc_group_grants
                where tenant_id = ? and issuer_uri = ? and external_group = ? and product_role = ?
                """, tenant.value(), issuer, grant.group(), grant.role());
    }
}
