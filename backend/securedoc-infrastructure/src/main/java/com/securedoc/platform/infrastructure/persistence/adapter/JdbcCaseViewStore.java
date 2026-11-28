package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.caseview.CaseViewSettings;
import com.securedoc.platform.application.port.out.CaseViewStore;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Table user_case_view (migration 089, task 0108). */
@Repository
public class JdbcCaseViewStore implements CaseViewStore {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCaseViewStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CaseViewSettings> find(TenantId tenantId, ActorId actorId) {
        List<CaseViewSettings> rows = jdbc.query("""
                select sort_key, sort_order, columns_list, density from user_case_view
                where tenant_id = :tenant and actor_id = :actor
                """, params(tenantId, actorId), (rs, n) -> {
            String columns = rs.getString("columns_list");
            return new CaseViewSettings(rs.getString("sort_key"), rs.getString("sort_order"),
                    columns == null ? null : columns.isEmpty() ? List.of() : Arrays.asList(columns.split("\n")),
                    rs.getString("density"));
        });
        return rows.stream().findFirst();
    }

    @Override
    public void save(TenantId tenantId, ActorId actorId, CaseViewSettings settings) {
        jdbc.update("""
                insert into user_case_view (tenant_id, actor_id, sort_key, sort_order, columns_list, density, updated_at)
                values (:tenant, :actor, :sort, :order, :columns, :density, clock_timestamp())
                on conflict (tenant_id, actor_id) do update set sort_key = excluded.sort_key,
                    sort_order = excluded.sort_order, columns_list = excluded.columns_list,
                    density = excluded.density, updated_at = excluded.updated_at
                """, params(tenantId, actorId).addValue("sort", settings.sort()).addValue("order", settings.order())
                .addValue("columns", settings.columns() == null ? null : String.join("\n", settings.columns()))
                .addValue("density", settings.density()));
    }

    private static MapSqlParameterSource params(TenantId tenantId, ActorId actorId) {
        return new MapSqlParameterSource("tenant", tenantId.value()).addValue("actor", actorId.value());
    }
}
