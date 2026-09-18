package com.securedoc.platform.infrastructure.intake;

import com.securedoc.platform.application.port.out.WatchedFolderLeasePort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** PostgreSQL-backed renewable lease with DB-time expiry and monotonic fencing generations. */
@Repository
public class JdbcWatchedFolderLeaseAdapter implements WatchedFolderLeasePort {
    private static final String ACQUIRE_OR_RENEW_SQL = """
            INSERT INTO watched_folder_leases
                (lease_name, owner_id, fencing_token, acquired_at, heartbeat_at, lease_expires_at)
            VALUES
                (:leaseName, :ownerId, 1, clock_timestamp(), clock_timestamp(),
                 clock_timestamp() + (:leaseMillis * interval '1 millisecond'))
            ON CONFLICT (lease_name) DO UPDATE SET
                owner_id = EXCLUDED.owner_id,
                fencing_token = CASE
                    WHEN watched_folder_leases.owner_id = EXCLUDED.owner_id
                        THEN watched_folder_leases.fencing_token
                    ELSE watched_folder_leases.fencing_token + 1
                END,
                acquired_at = CASE
                    WHEN watched_folder_leases.owner_id = EXCLUDED.owner_id
                        THEN watched_folder_leases.acquired_at
                    ELSE clock_timestamp()
                END,
                heartbeat_at = clock_timestamp(),
                lease_expires_at = clock_timestamp() + (:leaseMillis * interval '1 millisecond')
            WHERE watched_folder_leases.owner_id = EXCLUDED.owner_id
               OR watched_folder_leases.lease_expires_at <= clock_timestamp()
            RETURNING lease_name, owner_id, fencing_token, acquired_at, heartbeat_at, lease_expires_at
            """;

    private static final String CURRENT_SQL = """
            SELECT lease_name, owner_id, fencing_token, acquired_at, heartbeat_at, lease_expires_at
              FROM watched_folder_leases
             WHERE lease_name = :leaseName
            """;

    private static final String CURRENT_TIME_SQL = "SELECT clock_timestamp()";

    private static final String IS_CURRENT_SQL = """
            SELECT EXISTS (
                SELECT 1
                  FROM watched_folder_leases
                 WHERE lease_name = :leaseName
                   AND owner_id = :ownerId
                   AND fencing_token = :fencingToken
                   AND lease_expires_at > clock_timestamp()
            )
            """;

    private static final String RELEASE_SQL = """
            UPDATE watched_folder_leases
               SET lease_expires_at = clock_timestamp()
             WHERE lease_name = :leaseName
               AND owner_id = :ownerId
               AND fencing_token = :fencingToken
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcWatchedFolderLeaseAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LeaseSnapshot> acquireOrRenew(String leaseName, String ownerId, Duration leaseDuration) {
        long leaseMillis = Math.max(1000L, leaseDuration.toMillis());
        var params = new MapSqlParameterSource()
                .addValue("leaseName", leaseName)
                .addValue("ownerId", ownerId)
                .addValue("leaseMillis", leaseMillis);
        return jdbc.query(ACQUIRE_OR_RENEW_SQL, params, this::map).stream().findFirst();
    }

    @Override
    public Optional<LeaseSnapshot> current(String leaseName) {
        var params = new MapSqlParameterSource().addValue("leaseName", leaseName);
        return jdbc.query(CURRENT_SQL, params, this::map).stream().findFirst();
    }

    @Override
    public Instant currentTime() {
        java.sql.Timestamp value = jdbc.getJdbcOperations().queryForObject(CURRENT_TIME_SQL, java.sql.Timestamp.class);
        if (value == null) throw new IllegalStateException("Coordination database did not return current time");
        return value.toInstant();
    }

    @Override
    public boolean isCurrent(String leaseName, String ownerId, long fencingToken) {
        var params = new MapSqlParameterSource()
                .addValue("leaseName", leaseName)
                .addValue("ownerId", ownerId)
                .addValue("fencingToken", fencingToken);
        Boolean current = jdbc.queryForObject(IS_CURRENT_SQL, params, Boolean.class);
        return Boolean.TRUE.equals(current);
    }

    @Override
    public boolean release(String leaseName, String ownerId, long fencingToken) {
        var params = new MapSqlParameterSource()
                .addValue("leaseName", leaseName)
                .addValue("ownerId", ownerId)
                .addValue("fencingToken", fencingToken);
        return jdbc.update(RELEASE_SQL, params) == 1;
    }

    private LeaseSnapshot map(ResultSet rs, int rowNum) throws SQLException {
        return new LeaseSnapshot(
                rs.getString("lease_name"),
                rs.getString("owner_id"),
                rs.getLong("fencing_token"),
                rs.getTimestamp("acquired_at").toInstant(),
                rs.getTimestamp("heartbeat_at").toInstant(),
                rs.getTimestamp("lease_expires_at").toInstant());
    }
}
