package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.application.port.out.ExportDestinationStateRepository;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.DestinationState;
import com.securedoc.platform.domain.pipeline.ExportDestinationStatus;
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
import java.util.Set;
import java.util.UUID;

/** PostgreSQL export destination states (task 0027). A destination with no row is WORKING. Database time. */
@Repository
public class JdbcExportDestinationStateRepository implements ExportDestinationStateRepository {

    private static final String COLUMNS =
            "connection_id, tenant_id, state, paused_at, consecutive_failures, last_probe_at, last_lv_code, updated_at";

    private static final String ADD_FAILURE_SQL = """
            INSERT INTO export_destination_states
                (connection_id, tenant_id, state, consecutive_failures, last_lv_code, updated_at)
            VALUES (:connectionId, :tenantId, 'WORKING', 1, :lvCode, clock_timestamp())
            ON CONFLICT (connection_id) DO UPDATE SET
                consecutive_failures = export_destination_states.consecutive_failures + 1,
                last_lv_code = EXCLUDED.last_lv_code,
                updated_at = clock_timestamp()
            WHERE export_destination_states.tenant_id = EXCLUDED.tenant_id
            RETURNING consecutive_failures, state
            """;

    private static final String PAUSE_SQL = """
            UPDATE export_destination_states
               SET state = 'PAUSED', paused_at = clock_timestamp(), last_probe_at = clock_timestamp(),
                   updated_at = clock_timestamp()
             WHERE connection_id = :connectionId AND tenant_id = :tenantId AND state = 'WORKING'
               AND consecutive_failures >= :threshold
            """;

    private static final String ENSURE_ROW_SQL = """
            INSERT INTO export_destination_states (connection_id, tenant_id, state, consecutive_failures, updated_at)
            VALUES (:connectionId, :tenantId, 'WORKING', 0, clock_timestamp())
            ON CONFLICT (connection_id) DO NOTHING
            """;

    private static final String TRANSITION_SQL = """
            UPDATE export_destination_states
               SET state = :to,
                   paused_at = CASE WHEN :to = 'WORKING' THEN NULL ELSE clock_timestamp() END,
                   last_probe_at = CASE WHEN :to = 'PAUSED' THEN clock_timestamp() ELSE last_probe_at END,
                   consecutive_failures = CASE WHEN :to = 'WORKING' THEN 0 ELSE consecutive_failures END,
                   last_lv_code = COALESCE(:lvCode, last_lv_code),
                   updated_at = clock_timestamp()
             WHERE connection_id = :connectionId AND tenant_id = :tenantId AND state IN (:from)
            """;

    private static final String DUE_FOR_PROBE_SQL = "SELECT " + COLUMNS + """
             FROM export_destination_states
            WHERE state = 'PAUSED'
              AND (last_probe_at IS NULL OR last_probe_at <= clock_timestamp() - (:intervalMillis * interval '1 millisecond'))
            ORDER BY last_probe_at NULLS FIRST
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcExportDestinationStateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ExportDestinationStatus> find(TenantId tenantId, IntegrationConnectionId connectionId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM export_destination_states"
                        + " WHERE connection_id = :connectionId AND tenant_id = :tenantId",
                key(tenantId, connectionId), JdbcExportDestinationStateRepository::map).stream().findFirst();
    }

    @Override
    public List<ExportDestinationStatus> findByTenantId(TenantId tenantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM export_destination_states WHERE tenant_id = :tenantId",
                new MapSqlParameterSource("tenantId", tenantId.value()), JdbcExportDestinationStateRepository::map);
    }

    @Override
    public TransientFailure recordTransientFailure(TenantId tenantId, IntegrationConnectionId connectionId,
                                                   int pauseThreshold, String lvCode) {
        var params = key(tenantId, connectionId).addValue("lvCode", lvCode);
        var row = jdbc.query(ADD_FAILURE_SQL, params, (rs, n) -> new Object[] {
                rs.getInt("consecutive_failures"), DestinationState.valueOf(rs.getString("state"))}).stream()
                .findFirst().orElseThrow(() -> new IllegalStateException("Destination belongs to another tenant"));
        int failures = (Integer) row[0];
        DestinationState state = (DestinationState) row[1];
        boolean pausedNow = false;
        if (state == DestinationState.WORKING && failures >= pauseThreshold) {
            pausedNow = jdbc.update(PAUSE_SQL, key(tenantId, connectionId)
                    .addValue("threshold", Math.max(1, pauseThreshold))) == 1;
            state = pausedNow ? DestinationState.PAUSED
                    : find(tenantId, connectionId).map(ExportDestinationStatus::state).orElse(state);
        }
        return new TransientFailure(failures, pausedNow, state);
    }

    @Override
    public void resetFailures(TenantId tenantId, IntegrationConnectionId connectionId) {
        jdbc.update("UPDATE export_destination_states SET consecutive_failures = 0, updated_at = clock_timestamp()"
                + " WHERE connection_id = :connectionId AND tenant_id = :tenantId AND consecutive_failures <> 0",
                key(tenantId, connectionId));
    }

    @Override
    public boolean transition(TenantId tenantId, IntegrationConnectionId connectionId, Set<DestinationState> from,
                              DestinationState to, String lvCode) {
        if (from == null || from.isEmpty()) return false;
        if (from.contains(DestinationState.WORKING)) jdbc.update(ENSURE_ROW_SQL, key(tenantId, connectionId));
        var params = key(tenantId, connectionId)
                .addValue("from", from.stream().map(Enum::name).toList())
                .addValue("to", to.name())
                .addValue("lvCode", lvCode);
        return jdbc.update(TRANSITION_SQL, params) == 1;
    }

    @Override
    public List<ExportDestinationStatus> findPausedDueForProbe(Duration probeInterval, int limit) {
        var params = new MapSqlParameterSource()
                .addValue("intervalMillis", Math.max(0L, probeInterval.toMillis()))
                .addValue("limit", Math.max(1, limit));
        return jdbc.query(DUE_FOR_PROBE_SQL, params, JdbcExportDestinationStateRepository::map);
    }

    @Override
    public void recordProbe(TenantId tenantId, IntegrationConnectionId connectionId) {
        jdbc.update("UPDATE export_destination_states SET last_probe_at = clock_timestamp(), updated_at = clock_timestamp()"
                + " WHERE connection_id = :connectionId AND tenant_id = :tenantId", key(tenantId, connectionId));
    }

    private static MapSqlParameterSource key(TenantId tenantId, IntegrationConnectionId connectionId) {
        return new MapSqlParameterSource().addValue("tenantId", tenantId.value())
                .addValue("connectionId", connectionId.value());
    }

    static ExportDestinationStatus map(ResultSet rs, int rowNum) throws SQLException {
        return new ExportDestinationStatus(
                TenantId.of(rs.getObject("tenant_id", UUID.class)),
                IntegrationConnectionId.of(rs.getObject("connection_id", UUID.class)),
                DestinationState.valueOf(rs.getString("state")),
                instant(rs.getTimestamp("paused_at")),
                rs.getInt("consecutive_failures"),
                instant(rs.getTimestamp("last_probe_at")),
                rs.getString("last_lv_code"),
                instant(rs.getTimestamp("updated_at")));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
