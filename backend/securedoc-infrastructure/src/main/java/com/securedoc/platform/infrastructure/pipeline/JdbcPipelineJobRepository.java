package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.application.port.out.PipelineJobRepository;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineJobStatus;
import com.securedoc.platform.domain.pipeline.PipelinePriority;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * PostgreSQL pipeline job queue (task 0027). Every time is the database's clock, so all servers agree. A claim takes a
 * row with {@code FOR UPDATE SKIP LOCKED} and increases its {@code claim_token}; a result is saved only while the job
 * is RUNNING under that token and its claim has not run out.
 */
@Repository
public class JdbcPipelineJobRepository implements PipelineJobRepository {

    static final String COLUMNS = """
            id, tenant_id, stage, package_id, document_id, destination_id, item_id, export_cycle, priority, status,
            attempts, next_attempt_at, claim_owner, claim_until, claim_token, last_lv_code, last_detail,
            created_at, updated_at, resend, rebuilt, claim_expiries, first_failed_at, last_failed_at""";

    private static final String ENQUEUE_SQL = """
            INSERT INTO pipeline_jobs
                (id, tenant_id, stage, package_id, document_id, destination_id, item_id, export_cycle, priority,
                 status, attempts, next_attempt_at, claim_token, created_at, updated_at)
            VALUES
                (:id, :tenantId, :stage, :packageId, :documentId, :destinationId, :itemId, :exportCycle, :priority,
                 'READY', 0, clock_timestamp(), 0, clock_timestamp(), clock_timestamp())
            ON CONFLICT DO NOTHING
            """;

    static final String CLAIM_NEXT_SQL = """
            WITH next AS (
                SELECT j.id
                  FROM pipeline_jobs j
                  LEFT JOIN export_destination_states d
                    ON d.connection_id = j.destination_id AND d.tenant_id = j.tenant_id
                 WHERE j.stage IN (:stages)
                   AND j.status IN ('READY', 'WAITING_RETRY')
                   AND j.next_attempt_at <= clock_timestamp()
                   AND (d.state IS NULL OR d.state = 'WORKING')
                   AND (d.rate_limited_until IS NULL OR d.rate_limited_until <= clock_timestamp())
                   AND (j.destination_id IS NULL OR (
                        SELECT count(*) FROM pipeline_jobs r
                         WHERE r.destination_id = j.destination_id AND r.status = 'RUNNING')
                        < GREATEST(1, :perDestination - COALESCE(d.worker_reduction, 0)))
                 ORDER BY CASE j.priority WHEN 'URGENT' THEN 1 WHEN 'NORMAL' THEN 2 WHEN 'LOW' THEN 3 ELSE 4 END,
                          j.created_at, j.id
                 LIMIT 1
                 FOR UPDATE OF j SKIP LOCKED
            )
            UPDATE pipeline_jobs j
               SET status = 'RUNNING',
                   claim_owner = :owner,
                   claim_token = j.claim_token + 1,
                   claim_until = clock_timestamp() + (:leaseMillis * interval '1 millisecond'),
                   updated_at = clock_timestamp()
              FROM next
             WHERE j.id = next.id
            RETURNING j.*
            """;

    private static final String CLAIM_ONE_SQL = """
            UPDATE pipeline_jobs
               SET status = 'RUNNING',
                   claim_owner = :owner,
                   claim_token = claim_token + 1,
                   claim_until = clock_timestamp() + (:leaseMillis * interval '1 millisecond'),
                   updated_at = clock_timestamp()
             WHERE tenant_id = :tenantId AND id = :id AND status IN (:from)
            RETURNING *
            """;

    private static final String RENEW_SQL = """
            UPDATE pipeline_jobs
               SET claim_until = clock_timestamp() + (:leaseMillis * interval '1 millisecond'),
                   updated_at = clock_timestamp()
             WHERE id = :id AND claim_token = :token AND status = 'RUNNING' AND claim_until > clock_timestamp()
            """;

    private static final String IS_CURRENT_SQL = """
            SELECT EXISTS (SELECT 1 FROM pipeline_jobs
                            WHERE id = :id AND claim_token = :token AND status = 'RUNNING'
                              AND claim_until > clock_timestamp())
            """;

    static final String FINISH_SQL = """
            UPDATE pipeline_jobs
               SET status = :status,
                   attempts = :attempts,
                   next_attempt_at = clock_timestamp() + (:delayMillis * interval '1 millisecond'),
                   claim_owner = NULL,
                   claim_until = NULL,
                   last_lv_code = :lvCode,
                   last_detail = :detail,
                   resend = :resend,
                   rebuilt = rebuilt OR :rebuilt,
                   first_failed_at = CASE WHEN :failed THEN COALESCE(first_failed_at, clock_timestamp())
                                          ELSE first_failed_at END,
                   last_failed_at = CASE WHEN :failed THEN clock_timestamp() ELSE last_failed_at END,
                   updated_at = clock_timestamp()
             WHERE id = :id AND claim_token = :token AND status = 'RUNNING' AND claim_until > clock_timestamp()
            """;

    static final String RELEASE_EXPIRED_SQL = """
            UPDATE pipeline_jobs
               SET status = CASE WHEN claim_expiries + 1 >= :maxAttempts THEN 'NEEDS_IT' ELSE 'READY' END,
                   attempts = attempts + 1,
                   claim_expiries = claim_expiries + 1,
                   last_lv_code = CASE WHEN claim_expiries + 1 >= :maxAttempts THEN :exhaustedCode ELSE last_lv_code END,
                   last_detail = 'CLAIM_EXPIRED',
                   claim_owner = NULL,
                   claim_until = NULL,
                   next_attempt_at = clock_timestamp(),
                   updated_at = clock_timestamp()
             WHERE id IN (SELECT id FROM pipeline_jobs
                           WHERE status = 'RUNNING' AND claim_until <= clock_timestamp()
                           ORDER BY claim_until
                           LIMIT :limit
                           FOR UPDATE SKIP LOCKED)
            RETURNING *
            """;

    private static final String CHANGE_STATUS_SQL = """
            UPDATE pipeline_jobs
               SET status = :to,
                   attempts = :attempts,
                   last_lv_code = :lvCode,
                   last_detail = :detail,
                   resend = false,
                   next_attempt_at = clock_timestamp(),
                   claim_owner = NULL,
                   claim_until = NULL,
                   updated_at = clock_timestamp()
             WHERE tenant_id = :tenantId AND id = :id AND status = :from
            """;

    private static final String MAKE_DUE_SQL = """
            UPDATE pipeline_jobs
               SET status = 'READY', next_attempt_at = clock_timestamp(), updated_at = clock_timestamp()
             WHERE tenant_id = :tenantId AND destination_id = :destinationId AND status = 'WAITING_RETRY'
               AND last_detail IN ('DESTINATION_PAUSED', 'DESTINATIONS_UNREACHABLE')
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPipelineJobRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean enqueue(PipelineJob job) {
        var params = new MapSqlParameterSource()
                .addValue("id", job.id())
                .addValue("tenantId", job.tenantId().value())
                .addValue("stage", job.stage().name())
                .addValue("packageId", job.packageId().value())
                .addValue("documentId", job.documentId() == null ? null : job.documentId().value())
                .addValue("destinationId", job.destinationId() == null ? null : job.destinationId().value())
                .addValue("itemId", job.itemId())
                .addValue("exportCycle", job.exportCycle())
                .addValue("priority", job.priority().name());
        return jdbc.update(ENQUEUE_SQL, params) == 1;
    }

    @Override
    public Optional<PipelineJob> findByTenantIdAndId(TenantId tenantId, UUID id) {
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId.value()).addValue("id", id);
        return jdbc.query("SELECT " + COLUMNS + " FROM pipeline_jobs WHERE tenant_id = :tenantId AND id = :id",
                params, JdbcPipelineJobRepository::map).stream().findFirst();
    }

    @Override
    public List<PipelineJob> findByTenantIdAndPackageId(TenantId tenantId, PackageId packageId) {
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId.value())
                .addValue("packageId", packageId.value());
        return jdbc.query("SELECT " + COLUMNS + " FROM pipeline_jobs WHERE tenant_id = :tenantId"
                + " AND package_id = :packageId ORDER BY created_at, id", params, JdbcPipelineJobRepository::map);
    }

    @Override
    public List<PipelineJob> findByTenantIdAndStatus(TenantId tenantId, PipelineJobStatus status, int limit) {
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId.value())
                .addValue("status", status.name()).addValue("limit", Math.max(1, limit));
        return jdbc.query("SELECT " + COLUMNS + " FROM pipeline_jobs WHERE tenant_id = :tenantId"
                + " AND status = :status ORDER BY updated_at, created_at, id LIMIT :limit", params,
                JdbcPipelineJobRepository::map);
    }

    @Override
    public Optional<PipelineJob> claimNext(Set<PipelineStage> stages, String owner, Duration lease,
                                           int perDestinationLimit) {
        if (stages == null || stages.isEmpty()) return Optional.empty();
        var params = new MapSqlParameterSource()
                .addValue("stages", stages.stream().map(Enum::name).toList())
                .addValue("owner", owner)
                .addValue("leaseMillis", leaseMillis(lease))
                .addValue("perDestination", Math.max(1, perDestinationLimit));
        return jdbc.query(CLAIM_NEXT_SQL, params, JdbcPipelineJobRepository::map).stream().findFirst();
    }

    @Override
    public Optional<PipelineJob> claim(TenantId tenantId, UUID id, Set<PipelineJobStatus> from, String owner,
                                       Duration lease) {
        if (from == null || from.isEmpty()) return Optional.empty();
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("id", id)
                .addValue("from", from.stream().map(Enum::name).toList())
                .addValue("owner", owner)
                .addValue("leaseMillis", leaseMillis(lease));
        return jdbc.query(CLAIM_ONE_SQL, params, JdbcPipelineJobRepository::map).stream().findFirst();
    }

    @Override
    public boolean renew(UUID id, long claimToken, Duration lease) {
        var params = new MapSqlParameterSource().addValue("id", id).addValue("token", claimToken)
                .addValue("leaseMillis", leaseMillis(lease));
        return jdbc.update(RENEW_SQL, params) == 1;
    }

    @Override
    public boolean claimIsCurrent(UUID id, long claimToken) {
        var params = new MapSqlParameterSource().addValue("id", id).addValue("token", claimToken);
        return Boolean.TRUE.equals(jdbc.queryForObject(IS_CURRENT_SQL, params, Boolean.class));
    }

    @Override
    public boolean finish(UUID id, long claimToken, Finish result) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("token", claimToken)
                .addValue("status", result.status().name())
                .addValue("attempts", result.attempts())
                .addValue("delayMillis", result.retryDelay() == null ? 0L : Math.max(0L, result.retryDelay().toMillis()))
                .addValue("lvCode", result.lastLvCode())
                .addValue("detail", result.lastDetail())
                .addValue("resend", result.resend())
                .addValue("rebuilt", result.rebuilt())
                .addValue("failed", result.failed());
        return jdbc.update(FINISH_SQL, params) == 1;
    }

    @Override
    public List<PipelineJob> releaseExpiredClaims(int maxAttempts, String exhaustedLvCode, int limit) {
        var params = new MapSqlParameterSource()
                .addValue("maxAttempts", Math.max(1, maxAttempts))
                .addValue("exhaustedCode", exhaustedLvCode)
                .addValue("limit", Math.max(1, limit));
        return jdbc.query(RELEASE_EXPIRED_SQL, params, JdbcPipelineJobRepository::map);
    }

    @Override
    public boolean changeStatus(TenantId tenantId, UUID id, PipelineJobStatus from, PipelineJobStatus to, int attempts,
                                String lastLvCode, String lastDetail) {
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("id", id)
                .addValue("from", from.name())
                .addValue("to", to.name())
                .addValue("attempts", attempts)
                .addValue("lvCode", lastLvCode)
                .addValue("detail", lastDetail);
        return jdbc.update(CHANGE_STATUS_SQL, params) == 1;
    }

    @Override
    public int makeDueNow(TenantId tenantId, IntegrationConnectionId destinationId) {
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId.value())
                .addValue("destinationId", destinationId.value());
        return jdbc.update(MAKE_DUE_SQL, params);
    }

    private static final String NETWORK_DUE_SQL = """
            UPDATE pipeline_jobs
               SET status = 'READY', next_attempt_at = clock_timestamp(), updated_at = clock_timestamp()
             WHERE tenant_id = :tenantId AND status = 'WAITING_RETRY' AND last_detail = 'DESTINATIONS_UNREACHABLE'
            """;

    static final String RESEND_ALL_SQL = """
            UPDATE pipeline_jobs
               SET status = 'READY', resend = true, next_attempt_at = clock_timestamp(), updated_at = clock_timestamp()
             WHERE tenant_id = :tenantId AND destination_id = :destinationId AND status = 'WAITING_FOR_YOU'
            RETURNING id, created_at
            """;

    static final String RECENT_FAILING_CASES_SQL = """
            SELECT count(DISTINCT package_id) FROM pipeline_jobs
             WHERE tenant_id = :tenantId AND destination_id = :destinationId
               AND last_failed_at >= clock_timestamp() - (:withinMillis * interval '1 millisecond')
            """;

    @Override
    public List<PipelineJob> findExportJobsByPackageIds(TenantId tenantId, Collection<PackageId> packageIds) {
        if (packageIds == null || packageIds.isEmpty()) return List.of();
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId.value())
                .addValue("packageIds", packageIds.stream().map(PackageId::value).distinct().toList());
        return jdbc.query("SELECT " + COLUMNS + " FROM pipeline_jobs WHERE tenant_id = :tenantId"
                + " AND package_id IN (:packageIds) AND stage IN ('EXPORT', 'SPLIT_EXPORT')"
                + " ORDER BY created_at, id", params, JdbcPipelineJobRepository::map);
    }

    @Override
    public int makeNetworkWaitersDueNow(TenantId tenantId) {
        return jdbc.update(NETWORK_DUE_SQL, new MapSqlParameterSource("tenantId", tenantId.value()));
    }

    @Override
    public List<UUID> resendAll(TenantId tenantId, IntegrationConnectionId destinationId) {
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId.value())
                .addValue("destinationId", destinationId.value());
        record Row(UUID id, Instant createdAt) { }
        return jdbc.query(RESEND_ALL_SQL, params, (rs, n) -> new Row(rs.getObject("id", UUID.class),
                        instant(rs.getTimestamp("created_at")))).stream()
                .sorted(java.util.Comparator.comparing(Row::createdAt).thenComparing(Row::id))
                .map(Row::id).toList();
    }

    @Override
    public int countRecentlyFailingCases(TenantId tenantId, IntegrationConnectionId destinationId, Duration within) {
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId.value())
                .addValue("destinationId", destinationId.value())
                .addValue("withinMillis", Math.max(0L, within.toMillis()));
        Integer count = jdbc.queryForObject(RECENT_FAILING_CASES_SQL, params, Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public int countNeedsIt(TenantId tenantId, IntegrationConnectionId destinationId, String lvCode, UUID exceptJobId) {
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId.value())
                .addValue("destinationId", destinationId == null ? null : destinationId.value())
                .addValue("lvCode", lvCode)
                .addValue("exceptId", exceptJobId);
        String destination = destinationId == null ? "destination_id IS NULL" : "destination_id = :destinationId";
        Integer count = jdbc.queryForObject("SELECT count(*) FROM pipeline_jobs WHERE tenant_id = :tenantId AND "
                + destination + " AND status = 'NEEDS_IT' AND last_lv_code = :lvCode AND id <> :exceptId",
                params, Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public Map<UUID, Integer> countOpenByDestination(TenantId tenantId) {
        Map<UUID, Integer> counts = new HashMap<>();
        jdbc.query("SELECT destination_id, count(*) AS open_jobs FROM pipeline_jobs WHERE tenant_id = :tenantId"
                        + " AND destination_id IS NOT NULL AND status NOT IN ('DONE', 'STOPPED')"
                        + " AND stage IN ('EXPORT', 'SPLIT_EXPORT') GROUP BY destination_id",
                new MapSqlParameterSource("tenantId", tenantId.value()),
                rs -> { counts.put(rs.getObject("destination_id", UUID.class), rs.getInt("open_jobs")); });
        return counts;
    }

    private static long leaseMillis(Duration lease) {
        return Math.max(1000L, lease.toMillis());
    }

    static PipelineJob map(ResultSet rs, int rowNum) throws SQLException {
        UUID documentId = rs.getObject("document_id", UUID.class);
        UUID destinationId = rs.getObject("destination_id", UUID.class);
        return new PipelineJob(
                rs.getObject("id", UUID.class),
                TenantId.of(rs.getObject("tenant_id", UUID.class)),
                PipelineStage.valueOf(rs.getString("stage")),
                PackageId.of(rs.getObject("package_id", UUID.class)),
                documentId == null ? null : DocumentId.of(documentId),
                destinationId == null ? null : IntegrationConnectionId.of(destinationId),
                rs.getObject("item_id", UUID.class),
                rs.getInt("export_cycle"),
                PipelinePriority.valueOf(rs.getString("priority")),
                PipelineJobStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                instant(rs.getTimestamp("next_attempt_at")),
                rs.getString("claim_owner"),
                instant(rs.getTimestamp("claim_until")),
                rs.getLong("claim_token"),
                rs.getString("last_lv_code"),
                rs.getString("last_detail"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                rs.getBoolean("resend"),
                rs.getBoolean("rebuilt"),
                rs.getInt("claim_expiries"),
                instant(rs.getTimestamp("first_failed_at")),
                instant(rs.getTimestamp("last_failed_at")));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
