package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.casepage.CasePageSettings;
import com.securedoc.platform.application.port.out.CasePageSettingsStore;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Table case_page_settings (migration 091, task 0108). */
@Repository
public class JdbcCasePageSettingsStore implements CasePageSettingsStore {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCasePageSettingsStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<CasePageSettings> find(TenantId tenantId) {
        List<CasePageSettings> rows = jdbc.query("""
                select wait_amber_minutes, wait_red_minutes, empty_case_expiry_days from case_page_settings
                where tenant_id = :tenant
                """, new MapSqlParameterSource("tenant", tenantId.value()),
                (rs, n) -> new CasePageSettings(rs.getInt(1), rs.getInt(2), rs.getInt(3)));
        return rows.stream().findFirst();
    }

    @Override
    public void save(TenantId tenantId, ActorId actorId, CasePageSettings settings) {
        jdbc.update("""
                insert into case_page_settings (tenant_id, wait_amber_minutes, wait_red_minutes, empty_case_expiry_days,
                                                updated_at, updated_by)
                values (:tenant, :amber, :red, :days, clock_timestamp(), :actor)
                on conflict (tenant_id) do update set wait_amber_minutes = excluded.wait_amber_minutes,
                    wait_red_minutes = excluded.wait_red_minutes, empty_case_expiry_days = excluded.empty_case_expiry_days,
                    updated_at = excluded.updated_at, updated_by = excluded.updated_by
                """, new MapSqlParameterSource("tenant", tenantId.value()).addValue("actor", actorId.value())
                .addValue("amber", settings.waitAmberMinutes()).addValue("red", settings.waitRedMinutes())
                .addValue("days", settings.emptyCaseExpiryDays()));
    }

    @Override
    public OptionalInt shortestExpiryDays() {
        Integer days = jdbc.queryForObject("select min(empty_case_expiry_days) from case_page_settings",
                new MapSqlParameterSource(), Integer.class);
        return days == null ? OptionalInt.empty() : OptionalInt.of(days);
    }
}
