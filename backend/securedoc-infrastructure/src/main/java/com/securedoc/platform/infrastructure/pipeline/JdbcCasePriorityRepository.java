package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.application.port.out.CasePriorityRepository;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelinePriority;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Tables case_priorities and source_priorities (migration 082). */
@Repository
public class JdbcCasePriorityRepository implements CasePriorityRepository {

    /**
     * Held until the transaction ends: shared by whoever queues a job of the case ({@link JdbcPipelineJobRepository}),
     * alone by a change. So a job queued meanwhile is either seen by the change or queued after it.
     */
    static String lockSql(boolean shared) {
        return "SELECT pg_advisory_xact_lock" + (shared ? "_shared" : "")
                + "(hashtextextended('lexavault.case-priority:' || :packageId, 0))";
    }

    private static final String SAVE_SQL = """
            INSERT INTO case_priorities (package_id, tenant_id, priority, changed_at)
            VALUES (:packageId, :tenantId, :priority, clock_timestamp())
            ON CONFLICT (package_id) DO UPDATE SET priority = excluded.priority, changed_at = excluded.changed_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCasePriorityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveNew(TenantId tenantId, PackageId packageId, PipelinePriority priority) {
        if (priority == PipelinePriority.NORMAL) return;
        jdbc.update(SAVE_SQL, params(tenantId, packageId).addValue("priority", priority.name()));
    }

    @Override
    public PipelinePriority change(TenantId tenantId, PackageId packageId, PipelinePriority priority) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A priority is changed inside a transaction");
        }
        jdbc.query(lockSql(false), new MapSqlParameterSource("packageId", packageId.value().toString()), rs -> { });
        PipelinePriority before = find(tenantId, java.util.List.of(packageId)).get(packageId);
        var params = params(tenantId, packageId).addValue("priority", priority.name());
        jdbc.update(SAVE_SQL, params);
        jdbc.update("UPDATE pipeline_jobs SET priority = :priority, updated_at = clock_timestamp()"
                + " WHERE tenant_id = :tenantId AND package_id = :packageId AND priority <> :priority"
                + " AND status NOT IN ('RUNNING', 'DONE', 'STOPPED')", params);
        // A running job finishes; should it come back to the queue, it comes back with the new priority. One whose result
        // is being saved is passed over: that step may be waiting for this lock to queue the next job.
        jdbc.update("UPDATE pipeline_jobs SET priority = :priority WHERE id IN (SELECT id FROM pipeline_jobs"
                + " WHERE tenant_id = :tenantId AND package_id = :packageId AND priority <> :priority"
                + " AND status = 'RUNNING' FOR UPDATE SKIP LOCKED)", params);
        return before;
    }

    @Override
    public Map<PackageId, PipelinePriority> find(TenantId tenantId, Collection<PackageId> packageIds) {
        Map<PackageId, PipelinePriority> out = new HashMap<>();
        packageIds.forEach(id -> out.put(id, PipelinePriority.NORMAL));
        if (packageIds.isEmpty()) return out;
        jdbc.query("SELECT package_id, priority FROM case_priorities WHERE tenant_id = :tenantId"
                + " AND package_id IN (:ids)", new MapSqlParameterSource("tenantId", tenantId.value())
                .addValue("ids", packageIds.stream().map(PackageId::value).toList()), rs -> {
            out.put(PackageId.of(rs.getObject("package_id", UUID.class)),
                    PipelinePriority.valueOf(rs.getString("priority")));
        });
        return out;
    }

    @Override
    public Optional<PipelinePriority> sourceDefault(TenantId tenantId, String sourceKey) {
        return Optional.ofNullable(sourceDefaults(tenantId).get(sourceKey));
    }

    @Override
    public Map<String, PipelinePriority> sourceDefaults(TenantId tenantId) {
        Map<String, PipelinePriority> out = new HashMap<>();
        jdbc.query("SELECT source_key, priority FROM source_priorities WHERE tenant_id = :tenantId",
                new MapSqlParameterSource("tenantId", tenantId.value()), rs -> {
                    out.put(rs.getString("source_key"), PipelinePriority.valueOf(rs.getString("priority")));
                });
        return out;
    }

    @Override
    public void saveSourceDefault(TenantId tenantId, String sourceKey, PipelinePriority priority) {
        jdbc.update("INSERT INTO source_priorities (tenant_id, source_key, priority, changed_at)"
                + " VALUES (:tenantId, :key, :priority, clock_timestamp()) ON CONFLICT (tenant_id, source_key)"
                + " DO UPDATE SET priority = excluded.priority, changed_at = excluded.changed_at",
                new MapSqlParameterSource("tenantId", tenantId.value()).addValue("key", sourceKey)
                        .addValue("priority", priority.name()));
    }

    private static MapSqlParameterSource params(TenantId tenantId, PackageId packageId) {
        return new MapSqlParameterSource("tenantId", tenantId.value()).addValue("packageId", packageId.value());
    }
}
