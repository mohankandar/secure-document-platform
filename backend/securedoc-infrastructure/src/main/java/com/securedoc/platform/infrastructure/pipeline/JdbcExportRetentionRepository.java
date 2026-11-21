package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.application.port.out.ExportRetentionRepository;
import com.securedoc.platform.application.port.out.SplitCopyRetentionPort;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL retention periods per export destination, and the split copies they make due (task 0028). Hours only.
 * The master destination is the row with no destination id.
 */
@Repository
public class JdbcExportRetentionRepository implements ExportRetentionRepository, SplitCopyRetentionPort {

    private static final String UPSERT_SQL = """
            INSERT INTO export_retention_periods (id, tenant_id, destination_id, retention_hours, updated_by, updated_at)
            VALUES (:id, :tenantId, :destinationId, :hours, :actorId, clock_timestamp())
            ON CONFLICT (tenant_id, coalesce(destination_id, '00000000-0000-0000-0000-000000000000'::uuid))
            DO UPDATE SET retention_hours = EXCLUDED.retention_hours, updated_by = EXCLUDED.updated_by,
                          updated_at = clock_timestamp()
            """;

    /**
     * Delivered split copies still stored whose period from receipt has run out. The period is the split
     * destination's, else the master's, else the tenant's redacted-copy retention (24 h when no policy is stored). A
     * copy whose export job is still open is never due.
     */
    static final String DUE_SPLIT_COPIES_SQL = """
            SELECT d.id, d.tenant_id, d.package_id, d.destination_connection_id, d.artifact_id, d.delivered_at
              FROM derived_deliveries d
              JOIN artifacts a ON a.id = d.artifact_id AND a.status <> 'DELETED'
              LEFT JOIN export_retention_periods s
                ON s.tenant_id = d.tenant_id AND s.destination_id = d.destination_connection_id
              LEFT JOIN export_retention_periods m
                ON m.tenant_id = d.tenant_id AND m.destination_id IS NULL
              LEFT JOIN tenant_lifecycle_policies p ON p.tenant_id = d.tenant_id
             WHERE d.status = 'DELIVERED'
               AND d.delivered_at IS NOT NULL
               AND d.delivered_at + (COALESCE(s.retention_hours, m.retention_hours, p.redacted_cache_hours, 24)
                                     * interval '1 hour') <= :now
               AND NOT EXISTS (SELECT 1 FROM pipeline_jobs j
                                WHERE j.tenant_id = d.tenant_id AND j.package_id = d.package_id
                                  AND j.stage = 'SPLIT_EXPORT' AND j.item_id = d.id
                                  AND j.status NOT IN ('DONE', 'STOPPED'))
             ORDER BY d.delivered_at, d.id
             LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcExportRetentionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Integer> masterHours(TenantId tenantId) {
        return jdbc.query("SELECT retention_hours FROM export_retention_periods"
                        + " WHERE tenant_id = :tenantId AND destination_id IS NULL",
                new MapSqlParameterSource("tenantId", tenantId.value()), (rs, n) -> rs.getInt(1)).stream().findFirst();
    }

    @Override
    public Map<IntegrationConnectionId, Integer> splitHours(TenantId tenantId) {
        Map<IntegrationConnectionId, Integer> hours = new LinkedHashMap<>();
        jdbc.query("SELECT destination_id, retention_hours FROM export_retention_periods"
                        + " WHERE tenant_id = :tenantId AND destination_id IS NOT NULL",
                new MapSqlParameterSource("tenantId", tenantId.value()), rs -> {
                    hours.put(IntegrationConnectionId.of(rs.getObject("destination_id", UUID.class)),
                            rs.getInt("retention_hours"));
                });
        return hours;
    }

    @Override
    public void setMaster(TenantId tenantId, Integer hours, ActorId actorId) {
        if (hours == null) {
            jdbc.update("DELETE FROM export_retention_periods WHERE tenant_id = :tenantId AND destination_id IS NULL",
                    new MapSqlParameterSource("tenantId", tenantId.value()));
            return;
        }
        upsert(tenantId, null, hours, actorId);
    }

    @Override
    public void setSplit(TenantId tenantId, IntegrationConnectionId destinationId, Integer hours, ActorId actorId) {
        if (hours == null) {
            jdbc.update("DELETE FROM export_retention_periods WHERE tenant_id = :tenantId"
                            + " AND destination_id = :destinationId",
                    new MapSqlParameterSource().addValue("tenantId", tenantId.value())
                            .addValue("destinationId", destinationId.value()));
            return;
        }
        upsert(tenantId, destinationId.value(), hours, actorId);
    }

    private void upsert(TenantId tenantId, UUID destinationId, int hours, ActorId actorId) {
        jdbc.update(UPSERT_SQL, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("tenantId", tenantId.value())
                .addValue("destinationId", destinationId)
                .addValue("hours", hours)
                .addValue("actorId", actorId == null ? null : actorId.value()));
    }

    @Override
    public List<SplitCopy> findDueSplitCopies(Instant now, int limit) {
        return jdbc.query(DUE_SPLIT_COPIES_SQL, new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("limit", Math.max(1, limit)), (rs, n) -> {
                    UUID destination = rs.getObject("destination_connection_id", UUID.class);
                    return new SplitCopy(TenantId.of(rs.getObject("tenant_id", UUID.class)),
                            PackageId.of(rs.getObject("package_id", UUID.class)), rs.getObject("id", UUID.class),
                            destination == null ? null : IntegrationConnectionId.of(destination),
                            ArtifactId.of(rs.getObject("artifact_id", UUID.class)),
                            rs.getTimestamp("delivered_at").toInstant());
                });
    }
}
