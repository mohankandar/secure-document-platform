package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.application.port.out.CapacityRepository;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/** PostgreSQL store of task 0067. The counts are bounded, so a long queue costs no more than a short one. */
@Repository
public class JdbcCapacityRepository implements CapacityRepository {
    private static final String DUE_JOBS_SQL = """
            SELECT s.stage, (SELECT count(*) FROM (
                       SELECT 1 FROM pipeline_jobs j
                        WHERE j.stage = s.stage
                          AND j.status IN ('READY', 'WAITING_RETRY')
                          AND (j.status = 'READY' OR j.next_attempt_at <= clock_timestamp())
                          AND %s
                        LIMIT :most) due) AS jobs
              FROM unnest(string_to_array(:stages, ',')) AS s(stage)
            """;
    private static final String DUE_LIVE_SQL = DUE_JOBS_SQL.formatted("j.priority <> 'BULK'");
    private static final String DUE_BULK_SQL = DUE_JOBS_SQL.formatted("j.priority = 'BULK' AND EXISTS (SELECT 1 FROM"
            + " case_priorities c WHERE c.package_id = j.package_id AND (c.tenant_id || '/' || c.source_key) IN (:open))");

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCapacityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OptionalInt learnedLimit(String server) {
        List<Integer> found = jdbc.queryForList("SELECT learned_limit FROM ai_server_limits WHERE server = :server",
                new MapSqlParameterSource("server", server), Integer.class);
        return found.isEmpty() ? OptionalInt.empty() : OptionalInt.of(found.get(0));
    }

    @Override
    public void saveLearnedLimit(String server, int limit) {
        jdbc.update("INSERT INTO ai_server_limits (server, learned_limit, learned_at)"
                + " VALUES (:server, :limit, clock_timestamp()) ON CONFLICT (server) DO UPDATE"
                + " SET learned_limit = excluded.learned_limit, learned_at = excluded.learned_at",
                new MapSqlParameterSource("server", server).addValue("limit", limit));
    }

    @Override
    public Map<String, Integer> maximums() {
        Map<String, Integer> out = new HashMap<>();
        jdbc.query("SELECT name, maximum FROM capacity_maximums", rs -> {
            out.put(rs.getString("name"), rs.getInt("maximum"));
        });
        return out;
    }

    @Override
    public void setMaximum(String name, Integer maximum) {
        var params = new MapSqlParameterSource("name", name).addValue("maximum", maximum);
        if (maximum == null) {
            jdbc.update("DELETE FROM capacity_maximums WHERE name = :name", params);
        } else {
            jdbc.update("INSERT INTO capacity_maximums (name, maximum, changed_at)"
                    + " VALUES (:name, :maximum, clock_timestamp()) ON CONFLICT (name) DO UPDATE"
                    + " SET maximum = excluded.maximum, changed_at = excluded.changed_at", params);
        }
    }

    @Override
    public Map<PipelineStage, Integer> dueJobs(int most) {
        return due(DUE_LIVE_SQL, new MapSqlParameterSource("most", most));
    }

    @Override
    public Map<PipelineStage, Integer> dueBulkJobs(int most, java.util.Set<String> openBulkSources) {
        return openBulkSources.isEmpty() ? Map.of()
                : due(DUE_BULK_SQL, new MapSqlParameterSource("most", most).addValue("open", openBulkSources));
    }

    private Map<PipelineStage, Integer> due(String sql, MapSqlParameterSource params) {
        String stages = Arrays.stream(PipelineStage.values()).map(Enum::name).collect(Collectors.joining(","));
        Map<PipelineStage, Integer> out = new EnumMap<>(PipelineStage.class);
        jdbc.query(sql, params.addValue("stages", stages), rs -> {
            int jobs = rs.getInt("jobs");
            if (jobs > 0) out.put(PipelineStage.valueOf(rs.getString("stage")), jobs);
        });
        return out;
    }

    @Override
    public int waitingCases(int most) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM (SELECT 1 FROM document_packages"
                + " WHERE status = 'SUBMITTED' LIMIT :most) waiting", new MapSqlParameterSource("most", most), Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public long databaseBytes() {
        Long bytes = jdbc.queryForObject("SELECT pg_database_size(current_database())", new MapSqlParameterSource(),
                Long.class);
        return bytes == null ? 0 : bytes;
    }
}
