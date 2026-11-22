package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.application.port.out.FinishedJobCleanupPort;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL clean-up of finished pipeline jobs and cleared reachability alerts (task 0034). No transaction around
 * the batches: each delete commits on its own, so a large backlog never holds the table for long.
 *
 * <p>The job delete is per tenant so it uses {@code idx_pipeline_jobs_tenant_status (tenant_id, status, updated_at)}
 * from migration 043; no new index is needed.
 */
@Repository
public class JdbcFinishedJobCleanupRepository implements FinishedJobCleanupPort {

    /**
     * One batch of the tenant's finished jobs older than the cut-off, oldest first. Kept whatever their age:
     * <ul>
     *   <li>the case's final retention purge has not run ({@code package_lifecycles.purged_at} is null or missing);</li>
     *   <li>a redacted or split copy of the case is still stored (a split copy can outlive the final purge);</li>
     *   <li>the case still has an open job of any stage;</li>
     *   <li>a DONE job of a case still on the Cases list (it makes the case show "Exported").</li>
     * </ul>
     * Rows another session holds are skipped ({@code SKIP LOCKED}) and taken next time.
     */
    static final String DELETE_FINISHED_JOBS_SQL = """
            DELETE FROM pipeline_jobs
             WHERE id IN (
                SELECT j.id
                  FROM pipeline_jobs j
                  JOIN document_packages p ON p.id = j.package_id AND p.tenant_id = j.tenant_id
                  JOIN package_lifecycles l ON l.package_id = j.package_id AND l.tenant_id = j.tenant_id
                 WHERE j.tenant_id = :tenantId
                   AND j.status IN ('DONE', 'STOPPED')
                   AND j.updated_at < :cutoff
                   AND l.purged_at IS NOT NULL
                   AND (j.status = 'STOPPED' OR p.dashboard_hidden_at IS NOT NULL)
                   AND NOT EXISTS (SELECT 1 FROM pipeline_jobs o
                                    WHERE o.tenant_id = j.tenant_id AND o.package_id = j.package_id
                                      AND o.status NOT IN ('DONE', 'STOPPED'))
                   AND NOT EXISTS (SELECT 1 FROM documents d
                                     JOIN artifacts a ON a.document_id = d.id
                                    WHERE d.package_id = j.package_id
                                      AND a.artifact_type IN ('REDACTED', 'DERIVED_REDACTED')
                                      AND a.status <> 'DELETED')
                 ORDER BY j.updated_at, j.id
                 LIMIT :limit
                 FOR UPDATE OF j SKIP LOCKED)
            """;

    static final String DELETE_CLEARED_ALERTS_SQL = """
            DELETE FROM export_reachability_alerts
             WHERE tenant_id = :tenantId
               AND state = 'CLEARED'
               AND cleared_at IS NOT NULL
               AND cleared_at < :cutoff
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcFinishedJobCleanupRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TenantId> tenantIds() {
        return jdbc.query("SELECT id FROM tenants ORDER BY id", new MapSqlParameterSource(),
                (rs, n) -> TenantId.of(rs.getObject("id", UUID.class)));
    }

    @Override
    public int deleteFinishedJobs(TenantId tenantId, Instant cutoff, int limit) {
        return jdbc.update(DELETE_FINISHED_JOBS_SQL, new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("cutoff", Timestamp.from(cutoff))
                .addValue("limit", Math.max(1, limit)));
    }

    @Override
    public int deleteClearedAlerts(TenantId tenantId, Instant cutoff) {
        return jdbc.update(DELETE_CLEARED_ALERTS_SQL, new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("cutoff", Timestamp.from(cutoff)));
    }
}
