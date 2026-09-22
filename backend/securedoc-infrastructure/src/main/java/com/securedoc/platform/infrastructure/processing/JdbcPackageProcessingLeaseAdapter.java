package com.securedoc.platform.infrastructure.processing;

import com.securedoc.platform.application.port.out.PackageProcessingLeasePort;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL-backed package-processing lease with DB-time expiry and monotonic fencing generations. */
@Repository
public class JdbcPackageProcessingLeaseAdapter implements PackageProcessingLeasePort {

    private static final String ACQUIRE_OR_RENEW_SQL = """
            INSERT INTO package_processing_leases
                (package_id, tenant_id, owner_id, fencing_token, attempt_count, acquired_at, heartbeat_at, lease_expires_at)
            VALUES
                (:packageId, :tenantId, :ownerId, 1, 1, clock_timestamp(), clock_timestamp(),
                 clock_timestamp() + (:leaseMillis * interval '1 millisecond'))
            ON CONFLICT (package_id) DO UPDATE SET
                tenant_id = EXCLUDED.tenant_id,
                owner_id = EXCLUDED.owner_id,
                fencing_token = CASE
                    WHEN package_processing_leases.owner_id = EXCLUDED.owner_id
                        THEN package_processing_leases.fencing_token
                    ELSE package_processing_leases.fencing_token + 1
                END,
                acquired_at = CASE
                    WHEN package_processing_leases.owner_id = EXCLUDED.owner_id
                        THEN package_processing_leases.acquired_at
                    ELSE clock_timestamp()
                END,
                heartbeat_at = clock_timestamp(),
                lease_expires_at = clock_timestamp() + (:leaseMillis * interval '1 millisecond')
            WHERE package_processing_leases.tenant_id = EXCLUDED.tenant_id
              AND (package_processing_leases.owner_id = EXCLUDED.owner_id
                   OR package_processing_leases.lease_expires_at <= clock_timestamp())
            RETURNING package_id, tenant_id, owner_id, fencing_token, attempt_count, acquired_at, heartbeat_at, lease_expires_at
            """;

    private static final String ACQUIRE_PROCESSING_ATTEMPT_SQL = """
            INSERT INTO package_processing_leases
                (package_id, tenant_id, owner_id, fencing_token, attempt_count, acquired_at, heartbeat_at, lease_expires_at)
            SELECT
                :packageId, :tenantId, :ownerId, 1,
                CASE WHEN :recovery THEN 2 ELSE 1 END,
                clock_timestamp(), clock_timestamp(),
                clock_timestamp() + (:leaseMillis * interval '1 millisecond')
            WHERE (:recovery = FALSE OR :maxAttempts >= 2)
            ON CONFLICT (package_id) DO UPDATE SET
                tenant_id = EXCLUDED.tenant_id,
                owner_id = EXCLUDED.owner_id,
                fencing_token = CASE
                    WHEN package_processing_leases.owner_id = EXCLUDED.owner_id
                        THEN package_processing_leases.fencing_token
                    ELSE package_processing_leases.fencing_token + 1
                END,
                attempt_count = CASE
                    WHEN :recovery THEN package_processing_leases.attempt_count + 1
                    WHEN :resetAttemptBudget THEN 1
                    ELSE package_processing_leases.attempt_count
                END,
                acquired_at = clock_timestamp(),
                heartbeat_at = clock_timestamp(),
                lease_expires_at = clock_timestamp() + (:leaseMillis * interval '1 millisecond')
            WHERE package_processing_leases.tenant_id = EXCLUDED.tenant_id
              AND package_processing_leases.lease_expires_at <= clock_timestamp()
              AND (:recovery = FALSE OR package_processing_leases.attempt_count < :maxAttempts)
            RETURNING package_id, tenant_id, owner_id, fencing_token, attempt_count, acquired_at, heartbeat_at, lease_expires_at
            """;

    private static final String EXHAUSTION_CLAIM_SQL = """
            UPDATE package_processing_leases
               SET owner_id = :ownerId,
                   fencing_token = fencing_token + 1,
                   acquired_at = clock_timestamp(),
                   heartbeat_at = clock_timestamp(),
                   lease_expires_at = clock_timestamp() + (:leaseMillis * interval '1 millisecond')
             WHERE package_id = :packageId
               AND tenant_id = :tenantId
               AND fencing_token = :expectedFencingToken
               AND attempt_count = :expectedAttemptCount
               AND lease_expires_at <= clock_timestamp()
            RETURNING package_id, tenant_id, owner_id, fencing_token, attempt_count, acquired_at, heartbeat_at, lease_expires_at
            """;

    private static final String CURRENT_SQL = """
            SELECT package_id, tenant_id, owner_id, fencing_token, attempt_count, acquired_at, heartbeat_at, lease_expires_at
              FROM package_processing_leases
             WHERE package_id = :packageId
            """;


    private static final String EXPIRED_SQL = """
            SELECT l.package_id, l.tenant_id, l.owner_id, l.fencing_token, l.attempt_count,
                   l.acquired_at, l.heartbeat_at, l.lease_expires_at
              FROM package_processing_leases l
              JOIN document_packages p
                ON p.id = l.package_id AND p.tenant_id = l.tenant_id
             WHERE l.lease_expires_at <= clock_timestamp()
               AND p.status = 'PROCESSING'
             ORDER BY l.lease_expires_at ASC
             LIMIT :limit
            """;

    private static final String PROCESSING_WITHOUT_LEASE_SQL = """
            SELECT p.id AS package_id, p.tenant_id, p.updated_at
              FROM document_packages p
              LEFT JOIN package_processing_leases l ON l.package_id = p.id
             WHERE p.status = 'PROCESSING'
               AND l.package_id IS NULL
               AND p.updated_at <= clock_timestamp() - (:staleMillis * interval '1 millisecond')
             ORDER BY p.updated_at ASC
             LIMIT :limit
            """;

    private static final String CURRENT_TIME_SQL = "SELECT clock_timestamp()";

    private static final String IS_CURRENT_SQL = """
            SELECT EXISTS (
                SELECT 1
                  FROM package_processing_leases
                 WHERE package_id = :packageId
                   AND owner_id = :ownerId
                   AND fencing_token = :fencingToken
                   AND lease_expires_at > clock_timestamp()
            )
            """;

    private static final String RELEASE_SQL = """
            UPDATE package_processing_leases
               SET lease_expires_at = clock_timestamp(), heartbeat_at = clock_timestamp()
             WHERE package_id = :packageId
               AND owner_id = :ownerId
               AND fencing_token = :fencingToken
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPackageProcessingLeaseAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LeaseSnapshot> acquireOrRenew(PackageId packageId,
                                                  TenantId tenantId,
                                                  String ownerId,
                                                  Duration leaseDuration) {
        long leaseMillis = Math.max(1000L, leaseDuration.toMillis());
        var params = new MapSqlParameterSource()
                .addValue("packageId", packageId.value())
                .addValue("tenantId", tenantId.value())
                .addValue("ownerId", ownerId)
                .addValue("leaseMillis", leaseMillis);
        return jdbc.query(ACQUIRE_OR_RENEW_SQL, params, this::map).stream().findFirst();
    }

    @Override
    public Optional<LeaseSnapshot> acquireProcessingAttempt(PackageId packageId,
                                                            TenantId tenantId,
                                                            String ownerId,
                                                            Duration leaseDuration,
                                                            boolean recovery) {
        return acquireProcessingAttempt(packageId, tenantId, ownerId, leaseDuration, recovery, Integer.MAX_VALUE);
    }

    @Override
    public Optional<LeaseSnapshot> acquireProcessingAttempt(PackageId packageId,
                                                            TenantId tenantId,
                                                            String ownerId,
                                                            Duration leaseDuration,
                                                            boolean recovery,
                                                            int maxAttempts) {
        return acquireProcessingAttempt(packageId, tenantId, ownerId, leaseDuration, recovery, false, maxAttempts);
    }

    @Override
    public Optional<LeaseSnapshot> acquireProcessingAttempt(PackageId packageId,
                                                            TenantId tenantId,
                                                            String ownerId,
                                                            Duration leaseDuration,
                                                            boolean recovery,
                                                            boolean resetAttemptBudget,
                                                            int maxAttempts) {
        long leaseMillis = Math.max(1000L, leaseDuration.toMillis());
        var params = new MapSqlParameterSource()
                .addValue("packageId", packageId.value())
                .addValue("tenantId", tenantId.value())
                .addValue("ownerId", ownerId)
                .addValue("leaseMillis", leaseMillis)
                .addValue("recovery", recovery)
                .addValue("resetAttemptBudget", resetAttemptBudget)
                .addValue("maxAttempts", Math.max(1, maxAttempts));
        return jdbc.query(ACQUIRE_PROCESSING_ATTEMPT_SQL, params, this::map).stream().findFirst();
    }

    @Override
    public Optional<LeaseSnapshot> claimExpiredForExhaustion(PackageId packageId,
                                                              TenantId tenantId,
                                                              long expectedFencingToken,
                                                              int expectedAttemptCount,
                                                              String ownerId,
                                                              Duration leaseDuration) {
        long leaseMillis = Math.max(1000L, leaseDuration.toMillis());
        var params = new MapSqlParameterSource()
                .addValue("packageId", packageId.value())
                .addValue("tenantId", tenantId.value())
                .addValue("expectedFencingToken", expectedFencingToken)
                .addValue("expectedAttemptCount", Math.max(1, expectedAttemptCount))
                .addValue("ownerId", ownerId)
                .addValue("leaseMillis", leaseMillis);
        return jdbc.query(EXHAUSTION_CLAIM_SQL, params, this::map).stream().findFirst();
    }

    @Override
    public Optional<LeaseSnapshot> current(PackageId packageId) {
        var params = new MapSqlParameterSource().addValue("packageId", packageId.value());
        return jdbc.query(CURRENT_SQL, params, this::map).stream().findFirst();
    }

    @Override
    public List<LeaseSnapshot> findExpired(int limit) {
        var params = new MapSqlParameterSource().addValue("limit", Math.max(1, limit));
        return jdbc.query(EXPIRED_SQL, params, this::map);
    }

    @Override
    public List<OrphanedProcessingSnapshot> findProcessingWithoutLease(Duration staleAge, int limit) {
        var params = new MapSqlParameterSource()
                .addValue("staleMillis", Math.max(1000L, staleAge.toMillis()))
                .addValue("limit", Math.max(1, limit));
        return jdbc.query(PROCESSING_WITHOUT_LEASE_SQL, params, (rs, rowNum) ->
                new OrphanedProcessingSnapshot(
                        PackageId.of(rs.getObject("package_id", UUID.class)),
                        TenantId.of(rs.getObject("tenant_id", UUID.class)),
                        rs.getTimestamp("updated_at").toInstant()));
    }

    @Override
    public Instant currentTime() {
        java.sql.Timestamp value = jdbc.getJdbcOperations().queryForObject(CURRENT_TIME_SQL, java.sql.Timestamp.class);
        if (value == null) throw new IllegalStateException("Coordination database did not return current time");
        return value.toInstant();
    }

    @Override
    public boolean isCurrent(PackageId packageId, String ownerId, long fencingToken) {
        var params = new MapSqlParameterSource()
                .addValue("packageId", packageId.value())
                .addValue("ownerId", ownerId)
                .addValue("fencingToken", fencingToken);
        Boolean current = jdbc.queryForObject(IS_CURRENT_SQL, params, Boolean.class);
        return Boolean.TRUE.equals(current);
    }

    @Override
    public boolean release(PackageId packageId, String ownerId, long fencingToken) {
        var params = new MapSqlParameterSource()
                .addValue("packageId", packageId.value())
                .addValue("ownerId", ownerId)
                .addValue("fencingToken", fencingToken);
        return jdbc.update(RELEASE_SQL, params) == 1;
    }

    private LeaseSnapshot map(ResultSet rs, int rowNum) throws SQLException {
        return new LeaseSnapshot(
                PackageId.of(rs.getObject("package_id", UUID.class)),
                TenantId.of(rs.getObject("tenant_id", UUID.class)),
                rs.getString("owner_id"),
                rs.getLong("fencing_token"),
                rs.getInt("attempt_count"),
                rs.getTimestamp("acquired_at").toInstant(),
                rs.getTimestamp("heartbeat_at").toInstant(),
                rs.getTimestamp("lease_expires_at").toInstant());
    }
}
