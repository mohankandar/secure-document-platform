package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.application.port.out.BulkSourceRepository;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.BulkWindow;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Table bulk_sources (migration 083). */
@Repository
public class JdbcBulkSourceRepository implements BulkSourceRepository {
    private static final RowMapper<BulkSource> ROW = (rs, n) -> new BulkSource(
            TenantId.of(rs.getObject("tenant_id", UUID.class)), rs.getString("source_key"),
            new BulkWindow(rs.getObject("window_from", LocalTime.class), rs.getObject("window_to", LocalTime.class),
                    rs.getBoolean("weekends")), rs.getTimestamp("marked_at").toInstant());

    /** The cases of one source taken in as Bulk, also those a reviewer moved out of it. */
    private static final String CASES = """
              FROM case_priorities c
              JOIN document_packages p ON p.id = c.package_id AND p.tenant_id = c.tenant_id
             WHERE c.tenant_id = :tenantId AND c.source_key = :key
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcBulkSourceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<BulkSource> all() {
        return jdbc.query("SELECT * FROM bulk_sources ORDER BY tenant_id, source_key", ROW);
    }

    @Override
    public Optional<BulkSource> find(TenantId tenantId, String sourceKey) {
        return jdbc.query("SELECT * FROM bulk_sources WHERE tenant_id = :tenantId AND source_key = :key",
                params(tenantId, sourceKey), ROW).stream().findFirst();
    }

    @Override
    public void save(TenantId tenantId, String sourceKey, BulkWindow window) {
        jdbc.update("INSERT INTO bulk_sources (tenant_id, source_key, window_from, window_to, weekends, marked_at)"
                + " VALUES (:tenantId, :key, :from, :to, :weekends, clock_timestamp())"
                + " ON CONFLICT (tenant_id, source_key) DO UPDATE SET window_from = excluded.window_from,"
                + " window_to = excluded.window_to, weekends = excluded.weekends",
                params(tenantId, sourceKey).addValue("from", window.from()).addValue("to", window.to())
                        .addValue("weekends", window.weekends()));
    }

    @Override
    public void remove(TenantId tenantId, String sourceKey) {
        var params = params(tenantId, sourceKey);
        jdbc.update("DELETE FROM bulk_sources WHERE tenant_id = :tenantId AND source_key = :key", params);
        // A job queued at this moment may still say BULK; the queue takes it, because its case no longer does.
        jdbc.update("UPDATE case_priorities SET priority = 'LOW', changed_at = clock_timestamp()"
                + " WHERE tenant_id = :tenantId AND source_key = :key AND priority = 'BULK'", params);
        jdbc.update("UPDATE pipeline_jobs SET priority = 'LOW' WHERE id IN (SELECT j.id FROM pipeline_jobs j"
                + " JOIN case_priorities c ON c.package_id = j.package_id AND c.tenant_id = j.tenant_id"
                + " WHERE c.tenant_id = :tenantId AND c.source_key = :key AND j.priority = 'BULK'"
                + " AND j.status NOT IN ('DONE', 'STOPPED') FOR UPDATE OF j SKIP LOCKED)", params);
    }

    @Override
    public Counts counts(TenantId tenantId, String sourceKey) {
        return jdbc.queryForObject("SELECT count(*) FILTER (WHERE p.status IN ('COMPLETED', 'VAULTED')) AS done,"
                + " count(*) FILTER (WHERE p.status IN ('FAILED', 'REJECTED', 'CANCELLED')) AS failed,"
                + " count(*) AS everything" + CASES, params(tenantId, sourceKey), (rs, n) -> new Counts(
                rs.getLong("done"), rs.getLong("failed"),
                rs.getLong("everything") - rs.getLong("done") - rs.getLong("failed")));
    }

    @Override
    public long doneSince(TenantId tenantId, String sourceKey, Instant since) {
        Long done = jdbc.queryForObject("SELECT count(*)" + CASES + " AND p.status IN ('COMPLETED', 'VAULTED')"
                + " AND p.updated_at >= :since", params(tenantId, sourceKey).addValue("since", Timestamp.from(since)),
                Long.class);
        return done == null ? 0 : done;
    }

    private static MapSqlParameterSource params(TenantId tenantId, String sourceKey) {
        return new MapSqlParameterSource("tenantId", tenantId.value()).addValue("key", sourceKey);
    }
}
