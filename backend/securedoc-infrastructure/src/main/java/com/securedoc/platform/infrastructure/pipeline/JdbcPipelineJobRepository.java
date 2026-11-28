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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
            created_at, updated_at, resend, rebuilt, claim_expiries, first_failed_at, last_failed_at,
            page_from, page_to""";

    private static final String ENQUEUE_SQL = """
            INSERT INTO pipeline_jobs
                (id, tenant_id, stage, package_id, document_id, destination_id, item_id, export_cycle, priority,
                 status, attempts, next_attempt_at, claim_token, created_at, updated_at, page_from, page_to)
            VALUES
                (:id, :tenantId, :stage, :packageId, :documentId, :destinationId, :itemId, :exportCycle,
                 COALESCE((SELECT c.priority FROM case_priorities c WHERE c.package_id = :packageId), :priority),
                 'READY', 0, clock_timestamp(), 0, clock_timestamp(), clock_timestamp(), :pageFrom, :pageTo)
            ON CONFLICT DO NOTHING
            """;

    /**
     * Task 0038: when a job is due. READY means due now: it is only ever written with the database's clock at that
     * moment, and that clock can step back (Docker's virtual machine stepped it back 13 ms in task 0038's test runs),
     * so a READY job is never held back by its {@code next_attempt_at}. Only WAITING_RETRY waits for its time.
     */
    static final String DUE = """
            (j.status = 'READY' OR (j.status = 'WAITING_RETRY' AND j.next_attempt_at <= clock_timestamp()))""";

    /**
     * Task 0079: a BULK job only while its source's window is open and a worker may take one. One whose case is not Bulk
     * any more (its source was unmarked as the job was queued) is taken with them. With no window open the sub-query is
     * never run, so a long Bulk queue costs an idle worker nothing more.
     */
    static final String BULK_ALLOWED = """
            (j.priority <> 'BULK' OR :everyBulk OR :someBulk AND NOT EXISTS (
                    SELECT 1 FROM case_priorities c WHERE c.package_id = j.package_id AND c.priority = 'BULK'
                       AND (c.tenant_id || '/' || c.source_key) NOT IN (:openBulk)))""";

    /** A destination that takes jobs now: WORKING (or no state yet) and not waiting for a 429's Retry-After. */
    private static final String DESTINATION_OPEN = """
            (d.state IS NULL OR d.state = 'WORKING')
               AND (d.rate_limited_until IS NULL OR d.rate_limited_until <= clock_timestamp())""";

    /** Task 0073: the ranks of this turn ({@link PriorityTurns}). */
    private static final String RANK = """
            CASE j.priority WHEN 'URGENT' THEN :urgentRank WHEN 'NORMAL' THEN :normalRank WHEN 'LOW' THEN :lowRank
                 ELSE 4 END""";

    /**
     * Task 0049: fairness between entities. Within a priority, the entity after the one this server served last (by id,
     * round and round). One entity's flood never holds back another's work.
     * Task 0121 (owner 2026-11-28): cases take turns inside the entity the same way: the case after the one this server
     * served last in that entity (by id, round and round), then queue order within the case. A 5-page case that arrives
     * while a 120-page case is being read gets workers in between and finishes first. The cases served last come as two
     * lists (entity ids, case ids, one pair per entity), so the per-row look-up is over a handful of entities only.
     * Export jobs keep queue order between cases (a case is sent in the order it became ready): {@code :caseTurns} is
     * false when the claim is for an export stage.
     */
    private static final String FAIR_ORDER = """
            %s,
            (j.tenant_id <= :lastTenant), j.tenant_id,
            (:caseTurns AND j.package_id <= COALESCE((SELECT s.p FROM unnest(CAST(string_to_array(:servedTenants, ',') AS uuid[]),
                    CAST(string_to_array(:servedCases, ',') AS uuid[])) AS s(t, p) WHERE s.t = j.tenant_id), :noCase)),
            CASE WHEN :caseTurns THEN j.package_id END, j.queue_seq""".formatted(RANK);

    private static final UUID NO_TENANT = new UUID(0L, 0L);

    private static final String ORDER = """
            %s, j.queue_seq""".formatted(RANK);

    /**
     * Task 0036, changed in 0038: the next due job, in priority then queue order ({@code queue_seq}, never the clock),
     * with the limit of its destination. It locks no row: it only says which destination to try. The count of the
     * destination's running jobs is a first filter; the claim takes the destination's lock, counts again and then
     * takes that destination's first due job (see {@link #claimNext}).
     */
    static final String CANDIDATE_SQL = """
            SELECT j.id, j.tenant_id, j.destination_id,
                   GREATEST(1, :perDestination - COALESCE(d.worker_reduction, 0)) AS destination_limit
              FROM pipeline_jobs j
              LEFT JOIN export_destination_states d
                ON d.connection_id = j.destination_id AND d.tenant_id = j.tenant_id
             WHERE j.stage IN (:stages)
               AND j.status IN ('READY', 'WAITING_RETRY')
               AND %s
               AND %s
               AND (j.destination_id IS NULL OR (
                    SELECT count(*) FROM pipeline_jobs r
                     WHERE r.tenant_id = j.tenant_id AND r.destination_id = j.destination_id
                       AND r.status = 'RUNNING')
                    < GREATEST(1, :perDestination - COALESCE(d.worker_reduction, 0)))
             ORDER BY %s
             LIMIT 1
            """.formatted(DUE + " AND " + BULK_ALLOWED, DESTINATION_OPEN, FAIR_ORDER);

    /**
     * Task 0038: under the destination's lock, its first due job in queue order. {@code FOR UPDATE} without
     * {@code SKIP LOCKED}: no other claim can hold one of this destination's rows now (a claim locks a destination's
     * rows only while it holds that destination's lock), so it waits at most for a short status update.
     */
    static final String FIRST_AT_DESTINATION_SQL = """
            SELECT j.id
              FROM pipeline_jobs j
              LEFT JOIN export_destination_states d
                ON d.connection_id = j.destination_id AND d.tenant_id = j.tenant_id
             WHERE j.tenant_id = :tenantId AND j.destination_id = :destinationId
               AND j.stage IN (:stages)
               AND j.status IN ('READY', 'WAITING_RETRY')
               AND %s
               AND %s
             ORDER BY %s
             LIMIT 1
             FOR UPDATE OF j
            """.formatted(DUE + " AND " + BULK_ALLOWED, DESTINATION_OPEN, ORDER);

    /** A job without a destination has no limit: the first due one no other worker is taking. */
    static final String FIRST_WITHOUT_DESTINATION_SQL = """
            SELECT j.id
              FROM pipeline_jobs j
             WHERE j.destination_id IS NULL
               AND j.stage IN (:stages)
               AND j.status IN ('READY', 'WAITING_RETRY')
               AND %s
             ORDER BY %s
             LIMIT 1
             FOR UPDATE OF j SKIP LOCKED
            """.formatted(DUE + " AND " + BULK_ALLOWED, FAIR_ORDER);

    /**
     * One lock per (entity, destination), held until the claim's transaction ends: a 64-bit hash of a text that names
     * this use. Two pairs that happen to share a hash only wait for each other; they never break the limit.
     */
    static final String DESTINATION_LOCK_SQL =
            "SELECT pg_advisory_xact_lock(hashtextextended('lexavault.export-destination:' || :tenantId || ':' "
                    + "|| :destinationId, 0))";

    static final String RUNNING_AT_DESTINATION_SQL = """
            SELECT count(*) FROM pipeline_jobs
             WHERE tenant_id = :tenantId AND destination_id = :destinationId AND status = 'RUNNING'
            """;

    static final String TAKE_SQL = """
            UPDATE pipeline_jobs
               SET status = 'RUNNING',
                   claim_owner = :owner,
                   claim_token = claim_token + 1,
                   claim_until = clock_timestamp() + (:leaseMillis * interval '1 millisecond'),
                   updated_at = clock_timestamp()
             WHERE id = :id AND status IN ('READY', 'WAITING_RETRY')
            RETURNING *
            """;

    /** Candidates looked at in one call before waiting for the next poll (each in its own short transaction). */
    static final int CLAIM_TRIES = 3;

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
    private final TransactionTemplate transactions;
    /** The caller's transaction, or one of its own. */
    private final TransactionTemplate enqueue;
    /** Task 0049: the entity served last, per stage (set) claimed together, on this server. */
    private final Map<String, UUID> lastServed = new java.util.concurrent.ConcurrentHashMap<>();
    /** Task 0121: per stage (set) claimed together, on this server: the case served last in each entity. */
    private final Map<String, Map<UUID, UUID>> lastCase = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, PriorityTurns> turns = new java.util.concurrent.ConcurrentHashMap<>();

    /** For tests: a transaction manager on the template's own data source. */
    public JdbcPipelineJobRepository(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, new DataSourceTransactionManager(
                java.util.Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource())));
    }

    @Autowired
    public JdbcPipelineJobRepository(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.enqueue = new TransactionTemplate(transactionManager);
        this.transactions = new TransactionTemplate(transactionManager);
        // Each claim try is its own short transaction, also when a caller has one open.
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
                .addValue("priority", job.priority().name())
                .addValue("pageFrom", job.pageFrom(), java.sql.Types.INTEGER)
                .addValue("pageTo", job.pageTo(), java.sql.Types.INTEGER);
        // Task 0073: in one transaction with the case's lock, so a change of its priority at this moment is not missed.
        Boolean added = enqueue.execute(status -> {
            jdbc.query(JdbcCasePriorityRepository.lockSql(true),
                    new MapSqlParameterSource("packageId", job.packageId().value().toString()), rs -> { });
            return jdbc.update(ENQUEUE_SQL, params) == 1;
        });
        return Boolean.TRUE.equals(added);
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
                + " AND package_id = :packageId ORDER BY queue_seq", params, JdbcPipelineJobRepository::map);
    }

    @Override
    public List<PipelineJob> findByTenantIdAndStatus(TenantId tenantId, PipelineJobStatus status, int limit) {
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId.value())
                .addValue("status", status.name()).addValue("limit", Math.max(1, limit));
        return jdbc.query("SELECT " + COLUMNS + " FROM pipeline_jobs WHERE tenant_id = :tenantId"
                + " AND status = :status ORDER BY updated_at, queue_seq LIMIT :limit", params,
                JdbcPipelineJobRepository::map);
    }

    /**
     * Task 0036 (finding 1 of task 0035): the per-destination worker limit holds when workers claim at the same moment.
     * Task 0038: and a destination's jobs go out in queue order. Each try is one short transaction: find the next due
     * job without locking it (that only names the destination), take the advisory lock of its (entity, destination),
     * count that destination's RUNNING jobs again (a new statement, so it sees every claim committed before the lock
     * was granted), and, while there is room, lock and take that destination's first due job. A second worker of the
     * same destination waits for the first one's transaction (milliseconds), then sees its RUNNING job. Before 0038 the
     * row was locked first, with {@code SKIP LOCKED}, so the worker that won the lock could take the destination's
     * second job while the first was held by a worker still waiting. A try holds at most one advisory lock and locks a
     * destination's rows only while holding its lock, so two workers cannot deadlock.
     */
    @Override
    public Optional<PipelineJob> claimNext(Set<PipelineStage> stages, String owner, Duration lease,
                                           int perDestinationLimit) {
        return claimNext(stages, owner, lease, perDestinationLimit, null);
    }

    @Override
    public Optional<PipelineJob> claimNext(Set<PipelineStage> stages, String owner, Duration lease,
                                           int perDestinationLimit, Set<String> openBulkSources) {
        if (stages == null || stages.isEmpty()) return Optional.empty();
        var params = new MapSqlParameterSource()
                .addValue("stages", stages.stream().map(Enum::name).toList())
                .addValue("owner", owner)
                .addValue("leaseMillis", leaseMillis(lease))
                .addValue("perDestination", Math.max(1, perDestinationLimit))
                .addValue("everyBulk", openBulkSources == null || openBulkSources.contains(EVERY_BULK_SOURCE))
                .addValue("someBulk", openBulkSources != null && !openBulkSources.isEmpty())
                .addValue("openBulk", openBulkSources == null || openBulkSources.isEmpty() ? List.of("-") : openBulkSources);
        String turn = stages.stream().map(Enum::name).sorted().collect(java.util.stream.Collectors.joining(","));
        PriorityTurns priorityTurns = this.turns.computeIfAbsent(turn, t -> new PriorityTurns());
        PriorityTurns.Turn ranks = priorityTurns.next();
        params.addValue("urgentRank", ranks.urgent()).addValue("normalRank", ranks.normal())
                .addValue("lowRank", ranks.low());
        com.securedoc.platform.domain.pipeline.PipelinePriority taken = null;
        try {
            Optional<PipelineJob> job = claimInTurn(params, turn);
            taken = job.map(PipelineJob::priority).orElse(null);
            return job;
        } finally {
            priorityTurns.done(ranks, taken);
        }
    }

    private Optional<PipelineJob> claimInTurn(MapSqlParameterSource params, String turn) {
        for (int i = 0; i < CLAIM_TRIES; i++) {
            params.addValue("lastTenant", lastServed.getOrDefault(turn, NO_TENANT));
            Map<UUID, UUID> cases = lastCase.computeIfAbsent(turn, t -> new java.util.concurrent.ConcurrentHashMap<>());
            List<Map.Entry<UUID, UUID>> served = List.copyOf(cases.entrySet());
            params.addValue("servedTenants", served.stream().map(e -> e.getKey().toString())
                            .collect(java.util.stream.Collectors.joining(",")))
                    .addValue("servedCases", served.stream().map(e -> e.getValue().toString())
                            .collect(java.util.stream.Collectors.joining(",")))
                    .addValue("noCase", NO_TENANT)
                    .addValue("caseTurns", !turn.contains("EXPORT"));
            ClaimTry result = transactions.execute(status -> claimOnce(params));
            if (result == null || result.nothingDue()) return Optional.empty();
            if (result.claimed() != null) {
                lastServed.put(turn, result.claimed().tenantId().value());
                cases.put(result.claimed().tenantId().value(), result.claimed().packageId().value());
                return Optional.of(result.claimed());
            }
            // Its destination was full once the lock was held: look again (the first filter now sees it full).
        }
        return Optional.empty();
    }

    private record ClaimTry(PipelineJob claimed, boolean nothingDue) { }

    private record Candidate(UUID id, UUID tenantId, UUID destinationId, int limit) { }

    private ClaimTry claimOnce(MapSqlParameterSource base) {
        List<Candidate> found = jdbc.query(CANDIDATE_SQL, base, (rs, n) -> new Candidate(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("destination_id", UUID.class), rs.getInt("destination_limit")));
        if (found.isEmpty()) return new ClaimTry(null, true);
        Candidate candidate = found.get(0);
        List<UUID> first;
        if (candidate.destinationId() == null) {
            first = jdbc.queryForList(FIRST_WITHOUT_DESTINATION_SQL, base, UUID.class);
        } else {
            jdbc.query(DESTINATION_LOCK_SQL, new MapSqlParameterSource()
                    .addValue("tenantId", candidate.tenantId().toString())
                    .addValue("destinationId", candidate.destinationId().toString()), rs -> { });
            Integer running = jdbc.queryForObject(RUNNING_AT_DESTINATION_SQL, new MapSqlParameterSource()
                    .addValue("tenantId", candidate.tenantId())
                    .addValue("destinationId", candidate.destinationId()), Integer.class);
            if (running != null && running >= candidate.limit()) return new ClaimTry(null, false);
            first = jdbc.queryForList(FIRST_AT_DESTINATION_SQL, new MapSqlParameterSource(base.getValues())
                    .addValue("tenantId", candidate.tenantId())
                    .addValue("destinationId", candidate.destinationId()), UUID.class);
        }
        // Nothing left there (another worker took it, or the destination stopped taking jobs): look again.
        if (first.isEmpty()) return new ClaimTry(null, false);
        var take = new MapSqlParameterSource(base.getValues()).addValue("id", first.get(0));
        return jdbc.query(TAKE_SQL, take, JdbcPipelineJobRepository::map).stream().findFirst()
                .map(job -> new ClaimTry(job, false)).orElse(new ClaimTry(null, false));
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

    /** Task 0048: the same release, for the jobs of these stages only. */
    static final String RELEASE_EXPIRED_OF_STAGES_SQL = RELEASE_EXPIRED_SQL.replace(
            "WHERE status = 'RUNNING' AND claim_until <= clock_timestamp()",
            "WHERE status = 'RUNNING' AND claim_until <= clock_timestamp() AND stage IN (:stages)");

    @Override
    public List<PipelineJob> releaseExpiredClaims(Set<PipelineStage> stages, int maxAttempts, String exhaustedLvCode,
                                                  int limit) {
        if (stages == null || stages.isEmpty()) return List.of();
        var params = new MapSqlParameterSource()
                .addValue("stages", stages.stream().map(Enum::name).toList())
                .addValue("maxAttempts", Math.max(1, maxAttempts))
                .addValue("exhaustedCode", exhaustedLvCode)
                .addValue("limit", Math.max(1, limit));
        return jdbc.query(RELEASE_EXPIRED_OF_STAGES_SQL, params, JdbcPipelineJobRepository::map);
    }

    @Override
    public void lockCaseGate(TenantId tenantId, PackageId packageId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('lexavault.case-gate:' || :tenantId || ':' "
                        + "|| :packageId, 0))", new MapSqlParameterSource()
                        .addValue("tenantId", tenantId.value().toString())
                        .addValue("packageId", packageId.value().toString()), rs -> { });
    }

    static final String WAITING_FOR_GATE_SQL = """
            SELECT j.tenant_id, j.package_id
              FROM pipeline_jobs j
              JOIN document_packages p ON p.id = j.package_id AND p.tenant_id = j.tenant_id
             WHERE j.stage IN ('TEXT', 'FIND', 'VISION', 'REDACT')
               AND p.status IN ('PROCESSING', 'PENDING_REVIEW', 'IN_REVIEW')
               AND NOT EXISTS (SELECT 1 FROM pipeline_jobs o WHERE o.package_id = j.package_id
                                  AND ((o.stage IN ('TEXT', 'FIND', 'VISION', 'REDACT') AND o.status <> 'DONE'
                                        AND o.status <> 'STOPPED')
                                       OR (o.stage = 'EXPORT' AND o.export_cycle >= j.export_cycle)))
             GROUP BY j.tenant_id, j.package_id, p.status
            HAVING max(j.updated_at) < clock_timestamp() - (:quietMillis * interval '1 millisecond')
               AND (p.status = 'PROCESSING' OR bool_or(j.stage = 'REDACT'))
             ORDER BY max(j.updated_at)
             LIMIT :limit
            """;

    @Override
    public List<CaseRef> findCasesWaitingForTheirGate(Duration quietFor, int limit) {
        return jdbc.query(WAITING_FOR_GATE_SQL, new MapSqlParameterSource()
                        .addValue("quietMillis", quietFor.toMillis()).addValue("limit", Math.max(1, limit)),
                (rs, n) -> new CaseRef(TenantId.of(rs.getObject("tenant_id", UUID.class)),
                        PackageId.of(rs.getObject("package_id", UUID.class))));
    }

    @Override
    public boolean hasOpenStageJobs() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pipeline_jobs"
                + " WHERE stage IN ('TEXT', 'FIND', 'VISION', 'REDACT') AND status IN ('READY', 'RUNNING', 'WAITING_RETRY'))",
                new MapSqlParameterSource(), Boolean.class));
    }

    @Override
    public boolean hasOpenExportJobs() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pipeline_jobs"
                + " WHERE stage IN ('EXPORT', 'SPLIT_EXPORT') AND status NOT IN ('DONE', 'STOPPED'))",
                new MapSqlParameterSource(), Boolean.class));
    }

    @Override
    public int removeStageJobs(TenantId tenantId, PackageId packageId, int exportCycle) {
        return jdbc.update("DELETE FROM pipeline_jobs WHERE tenant_id = :tenantId AND package_id = :packageId"
                + " AND export_cycle = :cycle AND stage IN ('TEXT', 'FIND', 'VISION', 'REDACT')", new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value()).addValue("packageId", packageId.value())
                .addValue("cycle", exportCycle));
    }

    @Override
    public boolean hasStageJobs(TenantId tenantId, PackageId packageId) {
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId.value())
                .addValue("packageId", packageId.value());
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pipeline_jobs"
                + " WHERE package_id = :packageId AND tenant_id = :tenantId"
                + " AND stage IN ('TEXT', 'FIND', 'VISION', 'REDACT'))", params, Boolean.class));
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
            RETURNING id, queue_seq
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
                + " ORDER BY queue_seq", params, JdbcPipelineJobRepository::map);
    }

    @Override
    public int makeNetworkWaitersDueNow(TenantId tenantId) {
        return jdbc.update(NETWORK_DUE_SQL, new MapSqlParameterSource("tenantId", tenantId.value()));
    }

    @Override
    public List<UUID> resendAll(TenantId tenantId, IntegrationConnectionId destinationId) {
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId.value())
                .addValue("destinationId", destinationId.value());
        record Row(UUID id, long queueSeq) { }
        return jdbc.query(RESEND_ALL_SQL, params, (rs, n) -> new Row(rs.getObject("id", UUID.class),
                        rs.getLong("queue_seq"))).stream()
                .sorted(java.util.Comparator.comparingLong(Row::queueSeq))
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

    @Override
    public Map<UUID, Map<PipelineJobStatus, Integer>> countOpenByDestinationAndStatus(TenantId tenantId) {
        Map<UUID, Map<PipelineJobStatus, Integer>> counts = new HashMap<>();
        jdbc.query("SELECT destination_id, status, count(*) AS open_jobs FROM pipeline_jobs WHERE tenant_id = :tenantId"
                        + " AND destination_id IS NOT NULL AND status NOT IN ('DONE', 'STOPPED')"
                        + " AND stage IN ('EXPORT', 'SPLIT_EXPORT') GROUP BY destination_id, status",
                new MapSqlParameterSource("tenantId", tenantId.value()),
                rs -> {
                    counts.computeIfAbsent(rs.getObject("destination_id", UUID.class),
                                    k -> new java.util.EnumMap<>(PipelineJobStatus.class))
                            .put(PipelineJobStatus.valueOf(rs.getString("status")), rs.getInt("open_jobs"));
                });
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
                instant(rs.getTimestamp("last_failed_at")),
                rs.getObject("page_from", Integer.class),
                rs.getObject("page_to", Integer.class));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
