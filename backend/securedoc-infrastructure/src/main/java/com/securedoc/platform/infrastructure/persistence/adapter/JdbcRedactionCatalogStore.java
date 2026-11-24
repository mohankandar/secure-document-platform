package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.domain.business.RedactionCatalog;
import com.securedoc.platform.domain.business.RedactionStyle;
import com.securedoc.platform.domain.business.RegulationPack;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Migration 070 (task 0062): tenant_redaction_catalog (the on/off switch and style of each catalog kind per entity) and
 * tenant_regulation_packs (one row per pack in force). A kind with no row is off. A key or style this version doesn't
 * know is ignored (fail closed): an unknown kind is never switched on, an unknown style removes the value in full.
 */
@Component
public class JdbcRedactionCatalogStore {
    private static final Logger log = LoggerFactory.getLogger(JdbcRedactionCatalogStore.class);

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRedactionCatalogStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    /** What is stored for one entity. */
    public record Stored(Set<EntityType> enabled, Set<EntityType> keepLastFour, Set<RegulationPack> packs) {
    }

    public Stored load(TenantId tenantId) {
        MapSqlParameterSource tenant = new MapSqlParameterSource("tenant", tenantId.value());
        EnumSet<EntityType> enabled = EnumSet.noneOf(EntityType.class);
        EnumSet<EntityType> keepLastFour = EnumSet.noneOf(EntityType.class);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select kind_key, enabled, style from tenant_redaction_catalog where tenant_id = :tenant", tenant);
        for (Map<String, Object> row : rows) {
            EntityType kind = kind(String.valueOf(row.get("kind_key")));
            if (kind == null) {
                log.warn("TENANT_REDACTION_KIND_UNKNOWN takenAs=OFF");
                continue;
            }
            if (Boolean.TRUE.equals(row.get("enabled"))) enabled.add(kind);
            if (RedactionStyle.KEEP_LAST_FOUR.name().equals(row.get("style"))
                    && RedactionCatalog.lastFourAllowed().contains(kind)) {
                keepLastFour.add(kind);
            }
        }
        EnumSet<RegulationPack> packs = EnumSet.noneOf(RegulationPack.class);
        for (String key : jdbc.queryForList(
                "select pack_key from tenant_regulation_packs where tenant_id = :tenant", tenant, String.class)) {
            try {
                packs.add(RegulationPack.valueOf(key));
            } catch (IllegalArgumentException unknown) {
                log.warn("TENANT_REGULATION_PACK_UNKNOWN takenAs=OFF");
            }
        }
        return new Stored(enabled, keepLastFour, packs);
    }

    /** Writes one row per catalog kind, and one row per regulation pack in force. */
    public void save(TenantRedactionPolicy policy) {
        Timestamp at = Timestamp.from(policy.updatedAt());
        for (EntityType kind : RedactionCatalog.kinds()) {
            jdbc.update("""
                    insert into tenant_redaction_catalog (tenant_id, kind_key, enabled, style, updated_at)
                    values (:tenant, :kind, :enabled, :style, :at)
                    on conflict (tenant_id, kind_key) do update
                       set enabled = excluded.enabled, style = excluded.style, updated_at = excluded.updated_at
                    """, new MapSqlParameterSource("tenant", policy.tenantId().value())
                    .addValue("kind", kind.name())
                    .addValue("enabled", policy.isEnabled(kind))
                    .addValue("style", policy.styleOf(kind).name())
                    .addValue("at", at));
        }
        for (RegulationPack pack : RegulationPack.values()) {
            MapSqlParameterSource params = new MapSqlParameterSource("tenant", policy.tenantId().value())
                    .addValue("pack", pack.name()).addValue("at", at);
            if (policy.packs().contains(pack)) {
                jdbc.update("""
                        insert into tenant_regulation_packs (tenant_id, pack_key, updated_at)
                        values (:tenant, :pack, :at)
                        on conflict (tenant_id, pack_key) do nothing
                        """, params);
            } else {
                jdbc.update("delete from tenant_regulation_packs where tenant_id = :tenant and pack_key = :pack", params);
            }
        }
    }

    private static EntityType kind(String key) {
        try {
            EntityType kind = EntityType.valueOf(key);
            return RedactionCatalog.kinds().contains(kind) ? kind : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
