package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.application.port.out.ExportReachabilityAlertRepository;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL "LexaVault can't reach its destinations" alert, one row per tenant (task 0028). Database time. */
@Repository
public class JdbcExportReachabilityAlertRepository implements ExportReachabilityAlertRepository {

    private static final String COLUMNS = "tenant_id, state, raised_at, last_check_at, cleared_at";

    private static final String RAISE_SQL = """
            INSERT INTO export_reachability_alerts (tenant_id, state, raised_at, last_check_at, updated_at)
            VALUES (:tenantId, 'RAISED', clock_timestamp(), clock_timestamp(), clock_timestamp())
            ON CONFLICT (tenant_id) DO UPDATE SET
                state = 'RAISED', raised_at = clock_timestamp(), last_check_at = clock_timestamp(), cleared_at = NULL,
                updated_at = clock_timestamp()
            WHERE export_reachability_alerts.state = 'CLEARED'
            """;

    private static final String CLEAR_SQL = """
            UPDATE export_reachability_alerts
               SET state = 'CLEARED', cleared_at = clock_timestamp(), updated_at = clock_timestamp()
             WHERE tenant_id = :tenantId AND state = 'RAISED'
            """;

    private static final String DUE_SQL = "SELECT " + COLUMNS + """
             FROM export_reachability_alerts
            WHERE state = 'RAISED'
              AND (last_check_at IS NULL OR last_check_at <= clock_timestamp() - (:intervalMillis * interval '1 millisecond'))
            ORDER BY last_check_at NULLS FIRST
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcExportReachabilityAlertRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Alert> find(TenantId tenantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM export_reachability_alerts WHERE tenant_id = :tenantId",
                key(tenantId), JdbcExportReachabilityAlertRepository::map).stream().findFirst();
    }

    @Override
    public boolean raise(TenantId tenantId) {
        return jdbc.update(RAISE_SQL, key(tenantId)) == 1;
    }

    @Override
    public boolean clear(TenantId tenantId) {
        return jdbc.update(CLEAR_SQL, key(tenantId)) == 1;
    }

    @Override
    public void recordCheck(TenantId tenantId) {
        jdbc.update("UPDATE export_reachability_alerts SET last_check_at = clock_timestamp(),"
                + " updated_at = clock_timestamp() WHERE tenant_id = :tenantId", key(tenantId));
    }

    @Override
    public List<Alert> findRaisedDueForCheck(Duration interval, int limit) {
        return jdbc.query(DUE_SQL, new MapSqlParameterSource()
                .addValue("intervalMillis", Math.max(0L, interval.toMillis()))
                .addValue("limit", Math.max(1, limit)), JdbcExportReachabilityAlertRepository::map);
    }

    private static MapSqlParameterSource key(TenantId tenantId) {
        return new MapSqlParameterSource("tenantId", tenantId.value());
    }

    static Alert map(ResultSet rs, int rowNum) throws SQLException {
        return new Alert(TenantId.of(rs.getObject("tenant_id", UUID.class)), "RAISED".equals(rs.getString("state")),
                instant(rs.getTimestamp("raised_at")), instant(rs.getTimestamp("last_check_at")),
                instant(rs.getTimestamp("cleared_at")));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
