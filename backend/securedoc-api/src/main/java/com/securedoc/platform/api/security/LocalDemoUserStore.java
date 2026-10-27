package com.securedoc.platform.api.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** LOCAL-only simulated user assignments. Never consulted in OIDC mode. */
@Component
@ConditionalOnProperty(name = "securedoc.security.mode", havingValue = "LOCAL")
public class LocalDemoUserStore {
    private final JdbcTemplate jdbc;

    public LocalDemoUserStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DemoUser(UUID actorId, String displayName, String roleName) {}

    public List<DemoUser> list(UUID tenantId) {
        return jdbc.query("select actor_id, display_name, role_name from local_demo_users where tenant_id = ? order by display_name",
                (rs, row) -> new DemoUser(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), tenantId);
    }

    public Optional<DemoUser> find(UUID tenantId, UUID actorId) {
        return jdbc.query("select actor_id, display_name, role_name from local_demo_users where tenant_id = ? and actor_id = ?",
                (rs, row) -> new DemoUser(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), tenantId, actorId)
                .stream().findFirst();
    }

    public void upsert(UUID tenantId, UUID actorId, String name, String role, UUID changedBy) {
        jdbc.update("""
                insert into local_demo_users(tenant_id, actor_id, display_name, role_name, updated_by)
                values (?, ?, ?, ?, ?)
                on conflict (tenant_id, actor_id) do update
                    set display_name = excluded.display_name, role_name = excluded.role_name,
                        updated_by = excluded.updated_by, updated_at = now()
                """, tenantId, actorId, name, role, changedBy);
    }
}
