package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Table tenant_redaction_kinds (migration 047, task 0043): the on/off switch of each opt-in redaction kind per entity.
 * A kind with no row is off. A stored key the application does not know, or a kind that is not opt-in, is ignored
 * (fail closed: it is never switched on by a row it cannot read).
 */
@Component
public class JdbcTenantRedactionKindStore {
    private static final Logger log = LoggerFactory.getLogger(JdbcTenantRedactionKindStore.class);

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcTenantRedactionKindStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /** The opt-in kinds switched on for this entity. */
    public Set<EntityType> enabledKinds(TenantId tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select kind_key, enabled from tenant_redaction_kinds where tenant_id = :tenant",
                new MapSqlParameterSource("tenant", tenantId.value()));
        EnumSet<EntityType> enabled = EnumSet.noneOf(EntityType.class);
        for (Map<String, Object> row : rows) {
            if (!Boolean.TRUE.equals(row.get("enabled"))) continue;
            String key = String.valueOf(row.get("kind_key"));
            if (IMAGE_ONLY_REDACTED_PAGES.equals(key)) continue; // an output switch, not a kind
            EntityType kind = optInKind(key);
            if (kind == null) {
                log.warn("TENANT_REDACTION_KIND_UNKNOWN takenAs=OFF");
                continue;
            }
            enabled.add(kind);
        }
        return enabled;
    }

    /** Writes one row per opt-in kind: on when it is in {@code enabledTypes}, off otherwise. */
    public void save(TenantId tenantId, Set<EntityType> enabledTypes, Instant updatedAt) {
        for (EntityType kind : TenantRedactionPolicy.optInTypes()) {
            MapSqlParameterSource params = new MapSqlParameterSource("tenant", tenantId.value())
                    .addValue("kind", kind.name())
                    .addValue("enabled", enabledTypes.contains(kind))
                    .addValue("at", Timestamp.from(updatedAt));
            jdbc.update("""
                    insert into tenant_redaction_kinds (tenant_id, kind_key, enabled, updated_at)
                    values (:tenant, :kind, :enabled, :at)
                    on conflict (tenant_id, kind_key) do update
                       set enabled = excluded.enabled, updated_at = excluded.updated_at
                    """, params);
        }
    }

    /**
     * Task 0044: the redaction-output switch "image-only redacted pages", kept as one more row of this table (no new
     * migration; the task allows none). Like every row here, no row means off, so searchable redacted pages are on by
     * default. On means a Business Admin turned searchable redacted pages off.
     */
    public static final String IMAGE_ONLY_REDACTED_PAGES = "IMAGE_ONLY_REDACTED_PAGES";

    /** True unless the entity turned searchable redacted pages off. */
    public boolean searchableRedactedPages(TenantId tenantId) {
        List<Boolean> rows = jdbc.queryForList(
                "select enabled from tenant_redaction_kinds where tenant_id = :tenant and kind_key = :key",
                new MapSqlParameterSource("tenant", tenantId.value()).addValue("key", IMAGE_ONLY_REDACTED_PAGES),
                Boolean.class);
        return rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0));
    }

    public void saveSearchableRedactedPages(TenantId tenantId, boolean searchable, Instant updatedAt) {
        jdbc.update("""
                insert into tenant_redaction_kinds (tenant_id, kind_key, enabled, updated_at)
                values (:tenant, :kind, :enabled, :at)
                on conflict (tenant_id, kind_key) do update
                   set enabled = excluded.enabled, updated_at = excluded.updated_at
                """, new MapSqlParameterSource("tenant", tenantId.value())
                .addValue("kind", IMAGE_ONLY_REDACTED_PAGES)
                .addValue("enabled", !searchable)
                .addValue("at", Timestamp.from(updatedAt)));
    }

    private static EntityType optInKind(String key) {
        try {
            EntityType kind = EntityType.valueOf(key);
            return TenantRedactionPolicy.optInTypes().contains(kind) ? kind : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
