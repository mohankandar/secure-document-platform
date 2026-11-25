package com.securedoc.platform.infrastructure.operations;

import com.securedoc.platform.application.operations.daily.DailyReport;
import com.securedoc.platform.application.operations.model.ReportScope;
import com.securedoc.platform.application.port.out.DailyReportPort;
import com.securedoc.platform.domain.document.SensitivityLevel;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.ExportDestinations;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Task 0081: counts from LexaVault's own records, of one entity. No value, file name or person is read. */
@Repository
public class JdbcDailyReport implements DailyReportPort {
    private static final String LOCAL_FOLDER = "Local export folder";

    private static final String RECEIVED = """
            select count(*) as cases, coalesce(sum(f.document_count), 0) as documents, coalesce(sum(f.page_count), 0) as pages,
                   count(*) filter (where %s) as urgent
              from reporting_package_facts f
             where f.tenant_id = :tenant and f.received_at >= :from and f.received_at < :to%s
            """;

    private static final String URGENT = "exists (select 1 from case_priorities u where u.package_id = f.package_id"
            + " and u.tenant_id = f.tenant_id and u.priority = 'URGENT')";

    /** Each case once, however often it was exported in the period. */
    private static final String EXPORTED_CASES = """
            select f.* from reporting_package_facts f
             where f.tenant_id = :tenant%s
               and exists (select 1 from package_export_records x where x.tenant_id = f.tenant_id and x.package_id = f.package_id
                              and x.exported_at >= :from and x.exported_at < :to)
            """;

    /** The split documents delivered in the period, with the number of pages of each. */
    private static final String SPLIT_DONE = """
            select dd.*, cardinality(string_to_array(dd.page_numbers, ',')) as pages
              from derived_deliveries dd
              join reporting_package_facts f on f.tenant_id = dd.tenant_id and f.package_id = dd.package_id
             where dd.tenant_id = :tenant and dd.delivered_at >= :from and dd.delivered_at < :to%s
            """;

    /** Open export jobs per case, in the order of ExportJobService.caseExportState; and inline cases ready for export. */
    private static final String EXPORT_STATES = """
            select f.tenant_id, f.package_id,
                   case when bool_or(j.status = 'NEEDS_IT') then 'NEEDS_IT'
                        when bool_or(j.status = 'WAITING_FOR_YOU') then 'WAITING_FOR_YOU'
                        when bool_or(coalesce(s.state, 'WORKING') <> 'WORKING') then 'DESTINATION_PAUSED'
                        when bool_or(j.status = 'WAITING_RETRY') then 'WAITING_RETRY'
                        else 'READY' end as state,
                   min(j.created_at) as since,
                   min(j.last_lv_code) filter (where j.status = 'NEEDS_IT') as code
              from pipeline_jobs j
              join reporting_package_facts f on f.tenant_id = j.tenant_id and f.package_id = j.package_id
              left join export_destination_states s on s.tenant_id = j.tenant_id and s.connection_id = j.destination_id
             where j.tenant_id = :tenant and j.stage in ('EXPORT', 'SPLIT_EXPORT') and j.status not in ('DONE', 'STOPPED')%1$s
             group by f.tenant_id, f.package_id
            union all
            select f.tenant_id, f.package_id, 'READY', f.last_observed_at, null
              from reporting_package_facts f
             where f.tenant_id = :tenant and f.status in ('APPROVED', 'VAULTED') and f.first_delivered_at is null%1$s
               and not exists (select 1 from pipeline_jobs e where e.tenant_id = f.tenant_id and e.package_id = f.package_id
                                  and e.stage in ('EXPORT', 'SPLIT_EXPORT'))
            """;

    private static final String FINISHED = "(f.first_delivered_at is not null or f.status = 'COMPLETED')";

    /** FINISHED_BY_HAND also where the case was not exported afterwards. */
    private static final String HANDLED = """
            select count(*) filter (where finished and not by_hand and not reviewed) as no_person,
                   count(*) filter (where finished and not by_hand and reviewed) as after_review,
                   count(*) filter (where finished and by_hand) as by_hand,
                   count(*) filter (where not finished and status not in ('FAILED', 'REJECTED', 'CANCELLED')) as in_progress,
                   count(*) filter (where not finished and status = 'FAILED') as stopped,
                   count(*) filter (where not finished and status in ('REJECTED', 'CANCELLED')) as rejected
              from (select f.status, f.reviewed_flag as reviewed, %s as finished, %s as by_hand
                      from reporting_package_facts f
                     where f.tenant_id = :tenant and f.received_at >= :from and f.received_at < :to%s) h
            """;

    private static final String SOURCES = """
            select source_key, count(*) as received, count(*) filter (where finished) as exported,
                   count(*) filter (where not finished and status not in ('COMPLETED', 'FAILED', 'REJECTED', 'CANCELLED')) as in_progress,
                   count(*) filter (where not finished and status = 'FAILED') as stopped
              from (select f.status, %s as finished, %s as source_key
                      from reporting_package_facts f
                     where f.tenant_id = :tenant and f.received_at >= :from and f.received_at < :to%s) s
             group by source_key
            """;

    private static final String LEVELS = """
            select greatest(%s, %s) as level,
                   count(*) filter (where f.received_at >= :from and f.received_at < :to) as received,
                   count(*) filter (where exists (select 1 from package_export_records x where x.tenant_id = f.tenant_id
                         and x.package_id = f.package_id and x.exported_at >= :from and x.exported_at < :to)) as exported
              from reporting_package_facts f
              join document_packages p on p.id = f.package_id and p.tenant_id = f.tenant_id
              join documents d on d.package_id = p.id and d.tenant_id = p.tenant_id
             where f.tenant_id = :tenant%s
               and (f.received_at >= :from and f.received_at < :to or f.package_id in (select x.package_id
                         from package_export_records x where x.tenant_id = :tenant and x.exported_at >= :from and x.exported_at < :to))
             group by 1
            """;

    private static final String REFUSED = """
            select source_key, kind = 'DUPLICATE' as duplicate, lv_code as reason, count(*) as n
              from intake_refusals
             where tenant_id = :tenant and occurred_at >= :from and occurred_at < :to
             group by 1, 2, 3
            union all
            select 'folder:installed', status = 'DUPLICATE', coalesce(reason_code, ''), count(*)
              from intake_observations
             where tenant_id = :tenant and status in ('REJECTED', 'DUPLICATE') and updated_at >= :from and updated_at < :to
             group by 1, 2, 3
            """;

    private static final String REJECTED = """
            select r.reason_code as code, count(*) as n
              from package_rejections r
              join reporting_package_facts f on f.tenant_id = r.tenant_id and f.package_id = r.package_id
             where r.tenant_id = :tenant and r.rejected_at >= :from and r.rejected_at < :to%s
             group by 1
            """;

    /** Cases stopped now and not removed from Cases, by their latest problem; a case waiting for IT is an export failure. */
    private static final String STOPPED = """
            select i.lv_code, i.reason_code, coalesce(i.stage = 'DELIVERY', false) as delivery, count(*) as n
              from reporting_package_facts f
              join document_packages p on p.id = f.package_id and p.tenant_id = f.tenant_id and p.dashboard_hidden_at is null
              left join lateral (select pi.safe_metadata ->> 'lvCode' as lv_code, pi.reason_code, pi.stage
                                   from processing_incidents pi
                                  where pi.package_id = f.package_id and pi.tenant_id = f.tenant_id
                                  order by pi.last_observed_at desc limit 1) i on true
             where f.tenant_id = :tenant and f.status = 'FAILED'%s
               and not exists (select 1 from pipeline_jobs j where j.tenant_id = f.tenant_id and j.package_id = f.package_id
                                  and j.stage in ('EXPORT', 'SPLIT_EXPORT') and j.status = 'NEEDS_IT')
             group by 1, 2, 3
            """;

    /** The destination of the last try of a case's export. */
    private static final String DELIVERY_DESTINATION = """
            left join lateral (select a.connection_id from delivery_submissions s
                                 join delivery_attempts a on a.submission_id = s.id
                                where s.tenant_id = %1$s.tenant_id and s.package_id = %1$s.package_id%2$s
                                order by a.attempt_number desc limit 1) a on true
            """;

    private static final String FULL_DONE = """
            select coalesce(a.connection_id, case when x.destination_type = 'local-folder' then cast(:localFolder as uuid) end) as destination,
                   count(*) as cases, sum(f.document_count) as documents, sum(f.page_count) as pages
              from (select distinct on (package_id, destination_type) * from package_export_records
                     where tenant_id = :tenant and exported_at >= :from and exported_at < :to
                     order by package_id, destination_type, exported_at desc) x
              join reporting_package_facts f on f.tenant_id = x.tenant_id and f.package_id = x.package_id
            """ + DELIVERY_DESTINATION.formatted("x", " and s.export_cycle = x.cycle_number") + """
             where f.tenant_id = :tenant%s
             group by 1
            """;

    private static final String JOBS_OPEN = """
            select j.destination_id as destination, j.stage = 'SPLIT_EXPORT' as split,
                   count(distinct coalesce(case when j.stage = 'SPLIT_EXPORT' then j.item_id end, j.package_id))
                       filter (where j.status = 'NEEDS_IT') as failed,
                   count(distinct coalesce(case when j.stage = 'SPLIT_EXPORT' then j.item_id end, j.package_id))
                       filter (where j.status = 'WAITING_RETRY') as waiting
              from pipeline_jobs j
              join reporting_package_facts f on f.tenant_id = j.tenant_id and f.package_id = j.package_id
             where j.tenant_id = :tenant and j.stage in ('EXPORT', 'SPLIT_EXPORT') and j.status in ('NEEDS_IT', 'WAITING_RETRY')%1$s
             group by 1, 2
            union all
            select dd.destination_connection_id, true, count(*) filter (where dd.status = 'ATTENTION_REQUIRED'),
                   count(*) filter (where dd.status = 'RETRY_PENDING')
              from derived_deliveries dd
              join reporting_package_facts f on f.tenant_id = dd.tenant_id and f.package_id = dd.package_id
             where dd.tenant_id = :tenant and dd.status in ('ATTENTION_REQUIRED', 'RETRY_PENDING')%1$s
               and dd.destination_connection_id is not null
               and not exists (select 1 from pipeline_jobs j where j.tenant_id = dd.tenant_id and j.item_id = dd.id)
             group by 1
            union all
            select coalesce(a.connection_id, cast(:localFolder as uuid)), false, count(*), 0
              from reporting_package_facts f
              join document_packages p on p.id = f.package_id and p.tenant_id = f.tenant_id and p.dashboard_hidden_at is null
              join lateral (select pi.stage from processing_incidents pi where pi.package_id = f.package_id
                              and pi.tenant_id = f.tenant_id order by pi.last_observed_at desc limit 1) i on i.stage = 'DELIVERY'
            """ + DELIVERY_DESTINATION.formatted("f", "") + """
             where f.tenant_id = :tenant and f.status = 'FAILED'%1$s
               and not exists (select 1 from pipeline_jobs j where j.tenant_id = f.tenant_id and j.package_id = f.package_id
                                  and j.stage in ('EXPORT', 'SPLIT_EXPORT') and j.status = 'NEEDS_IT')
             group by 1
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDailyReport(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Counts counts(TenantId tenantId, Instant from, Instant to, ReportScope scope, Instant now) {
        MapSqlParameterSource params = params(tenantId, scope).addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to)).addValue("now", Timestamp.from(now));
        String where = where(scope);
        DailyReport.Received received = jdbc.queryForObject(RECEIVED.formatted(URGENT, where), params, (rs, n) ->
                new DailyReport.Received(rs.getLong("cases"), rs.getLong("documents"), rs.getLong("pages"), rs.getLong("urgent"), null));
        long splitDocuments = number("select count(*) from (" + SPLIT_DONE.formatted(where) + ") s", params);
        DailyReport.Exported exported = jdbc.queryForObject("""
                select count(*) as cases, coalesce(sum(f.document_count), 0) as documents, coalesce(sum(f.page_count), 0) as pages,
                       count(*) filter (where %s) as urgent from (%s) f""".formatted(URGENT, EXPORTED_CASES.formatted(where)), params,
                (rs, n) -> new DailyReport.Exported(rs.getLong("cases"), rs.getLong("documents"), rs.getLong("pages"),
                        splitDocuments, rs.getLong("urgent"), null));
        DailyReport.NotExported open = jdbc.queryForObject("""
                select count(*) as cases, count(*) filter (where state = 'READY') as ready,
                       count(*) filter (where state = 'WAITING_RETRY') as retry,
                       count(*) filter (where state = 'DESTINATION_PAUSED') as paused,
                       count(*) filter (where state = 'WAITING_FOR_YOU') as you,
                       count(*) filter (where %s) as urgent,
                       extract(epoch from :now - min(since)) as oldest
                  from (%s) f where state <> 'NEEDS_IT'""".formatted(URGENT, EXPORT_STATES.formatted(where)), params, (rs, n) -> {
            double oldest = rs.getDouble("oldest");
            Long oldestSeconds = rs.wasNull() ? null : Long.valueOf(Math.max(0, Math.round(oldest)));
            return new DailyReport.NotExported(rs.getLong("cases"), rs.getLong("ready"), rs.getLong("retry"),
                    rs.getLong("paused"), rs.getLong("you"), rs.getLong("urgent"), oldestSeconds);
        });
        DailyReport.Handled handled = jdbc.queryForObject(
                HANDLED.formatted(FINISHED, ReportingCaseRules.FINISHED_BY_HAND, where), params, (rs, n) ->
                        new DailyReport.Handled(rs.getLong("no_person"), rs.getLong("after_review"), rs.getLong("by_hand"),
                                rs.getLong("in_progress"), rs.getLong("stopped"), rs.getLong("rejected")));
        List<SourceCounts> sources = jdbc.query(
                SOURCES.formatted("f.first_delivered_at is not null", ReportCaseSql.sourceSystem("f", "f.package_id"), where), params, (rs, n) ->
                        new SourceCounts(rs.getString("source_key"), rs.getLong("received"), rs.getLong("exported"),
                                rs.getLong("in_progress"), rs.getLong("stopped")));
        Map<Integer, long[]> byLevel = new HashMap<>();
        jdbc.query(LEVELS.formatted(ReportCaseSql.rank("d.sensitivity_level"), ReportCaseSql.rank("p.sensitivity_level"), where),
                params, rs -> {
                    byLevel.put(rs.getInt("level"), new long[]{rs.getLong("received"), rs.getLong("exported")});
                });
        List<DailyReport.Level> levels = new ArrayList<>();
        for (SensitivityLevel level : SensitivityLevel.values()) {
            long[] n = byLevel.getOrDefault(ReportCaseSql.rank(level), new long[2]);
            levels.add(new DailyReport.Level(level.name(), n[0], n[1]));
        }
        // A refusal has no case, so no level.
        List<Refused> refused = !scope.everyLevel() ? List.of() : jdbc.query(REFUSED, params, (rs, n) ->
                        new Refused(rs.getString("source_key"), rs.getBoolean("duplicate"), rs.getString("reason"), rs.getLong("n")))
                .stream().filter(r -> scope.everySource() || scope.sourceSystem().equals(r.sourceKey())).toList();
        List<Stopped> stopped = jdbc.query(STOPPED.formatted(where), params, (rs, n) ->
                new Stopped(rs.getString("lv_code"), rs.getString("reason_code"), rs.getBoolean("delivery"), rs.getLong("n")));
        Map<String, Long> failed = byCode("select coalesce(code, '') as code, count(*) as n from (" + EXPORT_STATES.formatted(where)
                + ") f where state = 'NEEDS_IT' group by 1", params);
        boolean folder = scope.everyLevel() && (scope.everySource() || scope.sourceSystem().startsWith("folder:"));
        long trigger = !folder ? 0 : number("""
                select count(*) from (select distinct on (source_reference) status, visible_after from intake_observations
                  where tenant_id = :tenant order by source_reference, updated_at desc) o
                 where o.status = 'WAITING_FOR_TRIGGER' and o.visible_after <= :now""", params);
        return new Counts(received, exported, open, handled, levels, destinations(tenantId, where, params), sources, refused,
                byCode(REJECTED.formatted(where), params), stopped, failed, trigger);
    }

    private Map<String, Long> byCode(String sql, MapSqlParameterSource params) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query(sql, params, rs -> {
            out.put(rs.getString("code"), rs.getLong("n"));
        });
        return out;
    }

    private record Key(String id, boolean split) { }

    private List<DailyReport.Destination> destinations(TenantId tenantId, String where, MapSqlParameterSource params) {
        String local = ExportDestinations.localFolder(tenantId).value().toString();
        params.addValue("localFolder", local);
        Map<Key, long[]> numbers = new LinkedHashMap<>();
        jdbc.query(FULL_DONE.formatted(where), params, rs -> {
            add(numbers, new Key(text(rs.getString("destination")), false), rs.getLong("cases"), rs.getLong("documents"), rs.getLong("pages"), 0, 0);
        });
        jdbc.query("""
                select destination_connection_id as destination, count(*) as documents, sum(pages) as pages
                  from (%s) s group by 1""".formatted(SPLIT_DONE.formatted(where)), params, rs -> {
            add(numbers, new Key(text(rs.getString("destination")), true), rs.getLong("documents"), rs.getLong("documents"), rs.getLong("pages"), 0, 0);
        });
        jdbc.query(JOBS_OPEN.formatted(where), params, rs -> {
            long failed = rs.getLong("failed");
            long waiting = rs.getLong("waiting");
            if (failed + waiting > 0) add(numbers, new Key(text(rs.getString("destination")), rs.getBoolean("split")), 0, 0, 0, failed, waiting);
        });
        Map<String, String> names = new HashMap<>();
        names.put(local, LOCAL_FOLDER);
        Map<String, Boolean> paused = new HashMap<>();
        jdbc.query("select connection_id, state from export_destination_states where tenant_id = :tenant and state <> 'WORKING'",
                params, rs -> {
                    paused.put(rs.getString("connection_id"), true);
                });
        jdbc.query("""
                select id, name, enabled, purpose from integration_connections
                 where tenant_id = :tenant and purpose in ('DOCUMENT_STORE_DELIVERY', 'DERIVED_DOCUMENT_DELIVERY') order by name""",
                params, rs -> {
                    String id = rs.getString("id");
                    names.put(id, rs.getString("name"));
                    if (rs.getBoolean("enabled")) numbers.putIfAbsent(new Key(id, "DERIVED_DOCUMENT_DELIVERY".equals(rs.getString("purpose"))), new long[5]);
                });
        return numbers.entrySet().stream().map(e -> {
            String id = e.getKey().id();
            long[] n = e.getValue();
            return new DailyReport.Destination(id, names.getOrDefault(id, "A removed connection"), e.getKey().split(),
                    paused.containsKey(id), n[0], n[1], n[2], n[3], n[4]);
        }).sorted(java.util.Comparator.comparing(DailyReport.Destination::split)
                .thenComparing(DailyReport.Destination::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private static void add(Map<Key, long[]> numbers, Key key, long... more) {
        long[] sum = numbers.computeIfAbsent(key, k -> new long[5]);
        for (int i = 0; i < more.length; i++) sum[i] += more[i];
    }

    private static String text(String id) {
        return id == null ? "" : id;
    }

    @Override
    public List<Long> receivedIn(TenantId tenantId, List<Span> spans, ReportScope scope) {
        return perSpan(tenantId, spans, scope, "select count(*) from reporting_package_facts f where f.tenant_id = :tenant"
                + " and f.received_at >= :from and f.received_at < :to" + where(scope));
    }

    @Override
    public List<Long> exportedIn(TenantId tenantId, List<Span> spans, ReportScope scope) {
        return perSpan(tenantId, spans, scope, "select count(*) from (" + EXPORTED_CASES.formatted(where(scope)) + ") e");
    }

    private List<Long> perSpan(TenantId tenantId, List<Span> spans, ReportScope scope, String sql) {
        return spans.stream().map(s -> number(sql, params(tenantId, scope).addValue("from", Timestamp.from(s.from()))
                .addValue("to", Timestamp.from(s.to())))).toList();
    }

    @Override
    public Instant firstReceived(TenantId tenantId) {
        return jdbc.queryForObject("select min(received_at) from reporting_package_facts where tenant_id = :tenant",
                new MapSqlParameterSource("tenant", tenantId.value()), (rs, n) -> instant(rs, 1));
    }

    @Override
    public List<Receipt> receipts(TenantId tenantId, List<Span> spans) {
        List<Receipt> out = new ArrayList<>();
        String sql = "select " + ReportCaseSql.sourceSystem("f", "f.package_id") + " as source_key, f.received_at"
                + " from reporting_package_facts f where f.tenant_id = :tenant and f.received_at >= :from and f.received_at < :to";
        for (Span span : spans) {
            out.addAll(jdbc.query(sql, new MapSqlParameterSource("tenant", tenantId.value())
                    .addValue("from", Timestamp.from(span.from())).addValue("to", Timestamp.from(span.to())),
                    (rs, n) -> new Receipt(rs.getString(1), instant(rs, 2))));
        }
        return out;
    }

    @Override
    public Map<String, Instant> lastReceived(TenantId tenantId) {
        Map<String, Instant> out = new LinkedHashMap<>();
        jdbc.query("select source_key, max(received_at) from (select " + ReportCaseSql.sourceSystem("f", "f.package_id")
                + " as source_key, f.received_at from reporting_package_facts f where f.tenant_id = :tenant) s group by 1",
                new MapSqlParameterSource("tenant", tenantId.value()), rs -> {
                    out.put(rs.getString(1), instant(rs, 2));
                });
        return out;
    }

    @Override
    public List<KnownSource> knownSources(TenantId tenantId) {
        List<KnownSource> out = new ArrayList<>(jdbc.query("""
                select id, name, enabled from integration_connections where tenant_id = :tenant and purpose = 'INGESTION_SOURCE'
                """, new MapSqlParameterSource("tenant", tenantId.value()),
                (rs, n) -> new KnownSource(rs.getString("id"), rs.getString("name"), rs.getBoolean("enabled"))));
        return out;
    }

    private long number(String sql, MapSqlParameterSource params) {
        Long n = jdbc.queryForObject(sql, params, Long.class);
        return n == null ? 0 : n;
    }

    private static MapSqlParameterSource params(TenantId tenantId, ReportScope scope) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", tenantId.value());
        if (!scope.everySource()) params.addValue("sourceSystem", scope.sourceSystem());
        if (!scope.everyLevel()) params.addValue("levelRank", ReportCaseSql.levelRank(scope));
        return params;
    }

    private static String where(ReportScope scope) {
        return ReportCaseSql.clause(scope, "f", "f.package_id");
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp at = rs.getTimestamp(column);
        return at == null ? null : at.toInstant();
    }
}
