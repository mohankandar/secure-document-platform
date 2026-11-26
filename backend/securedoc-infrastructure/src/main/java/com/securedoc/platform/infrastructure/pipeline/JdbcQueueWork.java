package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.application.pipeline.QueueView;
import com.securedoc.platform.application.port.out.PipelineJobRepository;
import com.securedoc.platform.application.port.out.QueueWorkPort;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Task 0078: counts and times from pipeline_jobs and the states of cases and documents. Every time is the database's. */
@Repository
public class JdbcQueueWork implements QueueWorkPort {
    private static final String STAGE = "CASE j.stage WHEN 'SPLIT_EXPORT' THEN 'EXPORT' ELSE j.stage END";
    /** One entity, or each of them, so that the index of (entity, status, time) serves both. */
    private static final String ENTITIES = "j.tenant_id IN (SELECT t.id FROM tenants t WHERE (:tenant)::uuid IS NULL OR t.id = (:tenant)::uuid)";

    /** Due, and (task 0079) not a Bulk job whose window is closed: the same rule as the claim's. */
    private static final String DUE = "((j.status = 'READY' OR (j.status = 'WAITING_RETRY' AND j.next_attempt_at <= clock_timestamp()))"
            + " AND " + JdbcPipelineJobRepository.BULK_ALLOWED + ")";
    private static final String BULK_CASE_ALLOWED = """
            (:everyBulk OR NOT EXISTS (SELECT 1 FROM case_priorities c WHERE c.package_id = j.id AND c.priority = 'BULK'
                        AND (c.tenant_id || '/' || c.source_key) NOT IN (:openBulk)))""";

    private static final String OPEN_SQL = """
            SELECT %s AS stage,
                   count(*) FILTER (WHERE %s) AS waiting,
                   count(*) FILTER (WHERE j.status = 'RUNNING') AS running,
                   count(*) FILTER (WHERE j.status <> 'RUNNING' AND NOT %s) AS held,
                   extract(epoch FROM clock_timestamp() - min(j.created_at) FILTER (WHERE %s)) AS oldest
              FROM pipeline_jobs j
             WHERE j.status IN ('READY', 'RUNNING', 'WAITING_RETRY', 'PAUSED', 'WAITING_FOR_YOU', 'NEEDS_IT') AND %s
             GROUP BY 1
            """.formatted(STAGE, DUE, DUE, DUE, ENTITIES);

    /** A job is work of its stage from its creation to its end; the stage's busy time is these spans, overlaps once. */
    private static final String PACE_SQL = """
            WITH spans AS (
                SELECT %s AS stage, j.status, j.document_id,
                       greatest(j.created_at, clock_timestamp() - (:windowSeconds * interval '1 second')) AS begins,
                       CASE WHEN j.status = 'DONE' THEN j.updated_at ELSE clock_timestamp() END AS ends
                  FROM pipeline_jobs j
                 WHERE %s AND ((j.status = 'DONE' AND j.updated_at > clock_timestamp() - (:windowSeconds * interval '1 second'))
                           OR j.status = 'RUNNING' OR (j.status = 'READY' AND %s))),
            covered AS (
                SELECT stage, status, document_id, begins, ends,
                       max(ends) OVER (PARTITION BY stage ORDER BY begins, ends ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS before
                  FROM spans)
            SELECT stage,
                   count(*) FILTER (WHERE status = 'DONE') AS done,
                   count(DISTINCT document_id) FILTER (WHERE status = 'DONE') AS documents,
                   sum(extract(epoch FROM greatest(ends - greatest(begins, coalesce(before, begins)), interval '0'))) AS busy
              FROM covered GROUP BY stage
            """.formatted(STAGE, ENTITIES, JdbcPipelineJobRepository.BULK_ALLOWED);

    /**
     * Task 0098: the most jobs each stage finished in one minute (six 10-second steps, any minute) in the last hour. Every
     * server together: a finished job no longer says which server ran it.
     */
    private static final String BEST_SQL = """
            WITH steps AS (
                SELECT %s AS stage, floor(extract(epoch FROM j.updated_at) / 10)::bigint AS step, count(*) AS n
                  FROM pipeline_jobs j
                 WHERE j.status = 'DONE' AND j.updated_at > clock_timestamp() - (:bestSeconds * interval '1 second') AND %s
                 GROUP BY 1, 2),
            minutes AS (
                SELECT stage, sum(n) OVER (PARTITION BY stage ORDER BY step RANGE BETWEEN 5 PRECEDING AND CURRENT ROW) AS n
                  FROM steps)
            SELECT stage, max(n) AS best FROM minutes GROUP BY stage
            """.formatted(STAGE, ENTITIES);

    /** What will still reach each stage: documents and cases that are not stopped for a person. */
    private static final String TO_COME_SQL = """
            WITH cases AS (
                SELECT j.id, j.tenant_id, j.status FROM document_packages j WHERE j.status IN ('SUBMITTED', 'PROCESSING') AND %s AND %s)
            SELECT
                (SELECT count(*) FROM cases c JOIN documents d ON d.package_id = c.id AND d.tenant_id = c.tenant_id
                  WHERE c.status = 'SUBMITTED') AS text_documents,
                (SELECT count(*) FROM cases c JOIN documents d ON d.package_id = c.id AND d.tenant_id = c.tenant_id
                  WHERE d.status IN ('UPLOADED', 'EXTRACTING')) AS find_documents,
                (SELECT count(*) FROM cases c JOIN documents d ON d.package_id = c.id AND d.tenant_id = c.tenant_id
                  WHERE d.status IN ('UPLOADED', 'EXTRACTING', 'DETECTING', 'REDACTING')
                    AND NOT EXISTS (SELECT 1 FROM pipeline_jobs r WHERE r.package_id = c.id AND r.tenant_id = c.tenant_id
                                       AND r.document_id = d.id AND r.stage = 'REDACT'
                                       AND r.status IN ('READY', 'RUNNING', 'WAITING_RETRY'))) AS redact_documents,
                (SELECT count(*) FROM cases c
                  WHERE NOT EXISTS (SELECT 1 FROM pipeline_jobs e WHERE e.package_id = c.id AND e.tenant_id = c.tenant_id
                                       AND e.stage = 'EXPORT'
                                       AND e.status NOT IN ('DONE', 'STOPPED'))) AS export_cases
            """.formatted(ENTITIES, BULK_CASE_ALLOWED);

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcQueueWork(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Open(int waiting, int running, int held, Long oldest) { }

    private record Pace(int done, int documents, double busy) {
        /** Jobs per document of late; 1 (a document that is not split) while none is known. */
        double perDocument(double unknown) {
            return documents == 0 ? unknown : (double) done / documents;
        }
    }

    private Map<PipelineStage, Pace> pace(MapSqlParameterSource params) {
        Map<PipelineStage, Pace> out = new HashMap<>();
        jdbc.query(PACE_SQL, params, rs -> {
            out.put(PipelineStage.valueOf(rs.getString("stage")),
                    new Pace(rs.getInt("done"), rs.getInt("documents"), rs.getDouble("busy")));
        });
        return out;
    }

    @Override
    public Map<PipelineStage, QueueView.Work> work(TenantId tenantId, Duration window, Set<String> openBulkSources) {
        var params = new MapSqlParameterSource().addValue("tenant", tenantId == null ? null : tenantId.value().toString())
                .addValue("windowSeconds", window.toSeconds())
                .addValue("everyBulk", openBulkSources.contains(PipelineJobRepository.EVERY_BULK_SOURCE))
                .addValue("someBulk", !openBulkSources.isEmpty())
                .addValue("openBulk", openBulkSources.isEmpty() ? Set.of("-") : openBulkSources);
        Map<PipelineStage, Open> open = new HashMap<>();
        jdbc.query(OPEN_SQL, params, rs -> {
            double oldest = rs.getDouble("oldest");
            Long oldestSeconds = rs.wasNull() ? null : Long.valueOf(Math.max(0, Math.round(oldest)));
            open.put(PipelineStage.valueOf(rs.getString("stage")), new Open(rs.getInt("waiting"), rs.getInt("running"),
                    rs.getInt("held"), oldestSeconds));
        });
        Map<PipelineStage, Pace> pace = pace(params);
        // Jobs per document come from the whole window; the pace from the last minute while that has enough jobs.
        Map<PipelineStage, Pace> lately = pace(new MapSqlParameterSource(params.getValues())
                .addValue("windowSeconds", QueueView.LATELY.toSeconds()));
        Pace none = new Pace(0, 0, 0);
        Pace find = pace.getOrDefault(PipelineStage.FIND, none);
        // Vision works on some pages only: jobs per document that was searched.
        double visionPerDocument = find.documents() == 0 ? 0
                : (double) pace.getOrDefault(PipelineStage.VISION, none).done() / find.documents();
        Map<PipelineStage, Double> toCome = new EnumMap<>(PipelineStage.class);
        jdbc.query(TO_COME_SQL, params, rs -> {
            int search = rs.getInt("find_documents");
            toCome.put(PipelineStage.TEXT, rs.getInt("text_documents") * pace.getOrDefault(PipelineStage.TEXT, none).perDocument(1));
            toCome.put(PipelineStage.FIND, search * find.perDocument(1));
            toCome.put(PipelineStage.VISION, search * visionPerDocument);
            toCome.put(PipelineStage.REDACT, (double) rs.getInt("redact_documents"));
            toCome.put(PipelineStage.EXPORT, (double) rs.getInt("export_cases"));
        });
        Map<PipelineStage, Integer> best = new EnumMap<>(PipelineStage.class);
        jdbc.query(BEST_SQL, new MapSqlParameterSource(params.getValues())
                .addValue("bestSeconds", QueueView.BEST_WINDOW.toSeconds()), rs -> {
            best.put(PipelineStage.valueOf(rs.getString("stage")), rs.getInt("best"));
        });
        Map<PipelineStage, QueueView.Work> out = new EnumMap<>(PipelineStage.class);
        for (PipelineStage stage : toCome.keySet()) {
            Open o = open.getOrDefault(stage, new Open(0, 0, 0, null));
            Pace recent = lately.getOrDefault(stage, none);
            Pace p = recent.done() >= QueueView.ENOUGH_LATELY ? recent : pace.getOrDefault(stage, none);
            out.put(stage, new QueueView.Work(o.waiting(), o.running(), o.held(), o.oldest(),
                    (int) Math.round(toCome.get(stage)), p.done(), p.busy(), best.getOrDefault(stage, 0)));
        }
        return out;
    }
}
