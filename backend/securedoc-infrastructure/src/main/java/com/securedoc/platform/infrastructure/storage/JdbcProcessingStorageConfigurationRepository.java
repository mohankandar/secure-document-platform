package com.securedoc.platform.infrastructure.storage;

import com.securedoc.platform.application.port.out.ProcessingStorageConfigurationRepository;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.storage.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL-backed managed processing-storage configuration. */
@Component
public class JdbcProcessingStorageConfigurationRepository implements ProcessingStorageConfigurationRepository {
    private static final String PROVIDER_COLUMNS = "id,name,provider_type,root_path,status,configuration_version,created_by,created_at,updated_at";
    private final JdbcTemplate jdbc;

    public JdbcProcessingStorageConfigurationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ProcessingStorageProvider> findAllProviders() {
        return jdbc.query("select " + PROVIDER_COLUMNS + " from processing_storage_providers order by created_at,id", this::provider);
    }

    @Override
    public int nextProviderConfigurationVersion() {
        Long next = jdbc.queryForObject("select nextval('processing_storage_configuration_version_seq')", Long.class);
        if (next == null || next > Integer.MAX_VALUE) {
            throw new IllegalStateException("Processing storage configuration version is unavailable");
        }
        return next.intValue();
    }

    @Override
    public Optional<ProcessingStorageProvider> findProvider(UUID providerId) {
        List<ProcessingStorageProvider> rows = jdbc.query(
                "select " + PROVIDER_COLUMNS + " from processing_storage_providers where id=?",
                this::provider, providerId);
        return rows.stream().findFirst();
    }

    @Override
    public void saveProvider(ProcessingStorageProvider p) {
        jdbc.update("""
                insert into processing_storage_providers
                    (id,name,provider_type,root_path,status,configuration_version,created_by,created_at,updated_at)
                values (?,?,?,?,?,?,?,?,?)
                on conflict (id) do update set
                    name=excluded.name, provider_type=excluded.provider_type, root_path=excluded.root_path,
                    status=excluded.status, configuration_version=excluded.configuration_version,
                    updated_at=excluded.updated_at
                """, p.id(), p.name(), p.providerType().name(), p.rootPath(), p.status().name(),
                p.configurationVersion(), p.createdBy(), sqlTimestamp(p.createdAt()), sqlTimestamp(p.updatedAt()));
    }

    @Override
    public Optional<ProcessingStorageAssignment> findDeploymentDefault() {
        return assignmentByScope("DEPLOYMENT");
    }

    @Override
    public Optional<ProcessingStorageAssignment> findTenantOverride(TenantId tenantId) {
        return assignmentByScope("TENANT:" + tenantId.value());
    }

    @Override
    public List<ProcessingStorageAssignment> findTenantOverrides() {
        return jdbc.query("""
                select id,tenant_id,provider_id,activated_by,activated_at
                from processing_storage_assignments where tenant_id is not null order by tenant_id
                """, this::assignment);
    }

    @Override
    public void activateDeploymentDefault(UUID providerId, UUID actorId, Instant now) {
        upsertAssignment("DEPLOYMENT", null, providerId, actorId, now);
    }

    @Override
    public void activateTenantOverride(TenantId tenantId, UUID providerId, UUID actorId, Instant now) {
        upsertAssignment("TENANT:" + tenantId.value(), tenantId.value(), providerId, actorId, now);
    }

    @Override
    public void clearTenantOverride(TenantId tenantId) {
        jdbc.update("delete from processing_storage_assignments where scope_key=?", "TENANT:" + tenantId.value());
    }

    @Override
    public int nextProcessingCycle(TenantId tenantId, PackageId packageId) {
        Integer successful = jdbc.queryForObject("""
                select coalesce((select successful_export_cycles from package_lifecycles
                                 where package_id=? and tenant_id=?),0)
                """, Integer.class, packageId.value(), tenantId.value());
        return (successful == null ? 0 : successful) + 1;
    }

    @Override
    public Optional<UUID> inferCycleOneProviderFromExistingArtifacts(TenantId tenantId, PackageId packageId) {
        List<UUID> rows = jdbc.query("""
                select distinct p.id
                from artifacts a
                join documents d on d.id=a.document_id and d.tenant_id=a.tenant_id
                join processing_storage_providers p on
                     (a.storage_provider=p.id::text)
                     or (a.storage_provider='local' and a.storage_bucket=p.root_path)
                where a.tenant_id=? and d.package_id=? and a.artifact_type='ORIGINAL'
                  and a.status<>'DELETED'
                order by p.id
                """, (rs,rowNum) -> rs.getObject(1, UUID.class), tenantId.value(), packageId.value());
        return rows.size() == 1 ? Optional.of(rows.get(0)) : Optional.empty();
    }

    @Override
    public Optional<UUID> findPinnedProvider(TenantId tenantId, PackageId packageId, int processingCycle) {
        List<UUID> rows = jdbc.query("""
                select provider_id from processing_storage_cycle_pins
                where tenant_id=? and package_id=? and processing_cycle=?
                """, (rs, rowNum) -> rs.getObject(1, UUID.class), tenantId.value(), packageId.value(), processingCycle);
        return rows.stream().findFirst();
    }

    @Override
    public UUID pinProvider(TenantId tenantId, PackageId packageId, int processingCycle, UUID providerId, Instant now) {
        jdbc.update("""
                insert into processing_storage_cycle_pins(tenant_id,package_id,processing_cycle,provider_id,created_at)
                values (?,?,?,?,?) on conflict (tenant_id,package_id,processing_cycle) do nothing
                """, tenantId.value(), packageId.value(), processingCycle, providerId, sqlTimestamp(now));
        return findPinnedProvider(tenantId, packageId, processingCycle)
                .orElseThrow(() -> new IllegalStateException("Processing storage cycle pin could not be established"));
    }

    @Override
    public void heartbeatNode(String nodeId, UUID processInstanceId, Instant now) {
        jdbc.update("""
                insert into processing_storage_nodes(node_id,process_instance_id,heartbeat_at) values (?,?,?)
                on conflict (node_id,process_instance_id) do update set heartbeat_at=excluded.heartbeat_at
                """, nodeId, processInstanceId, sqlTimestamp(now));
    }

    @Override
    public void saveNodeValidation(ProcessingStorageNodeValidation v) {
        jdbc.update("""
                insert into processing_storage_node_validations
                    (node_id,process_instance_id,provider_id,status,failure_code,checked_at)
                values (?,?,?,?,?,?)
                on conflict (node_id,process_instance_id,provider_id) do update set
                    status=excluded.status,failure_code=excluded.failure_code,checked_at=excluded.checked_at
                """, v.nodeId(), v.processInstanceId(), v.providerId(), v.status().name(), v.failureCode(), sqlTimestamp(v.checkedAt()));
    }

    @Override
    public List<ProcessingStorageNodeValidation> findActiveNodeValidations(UUID providerId, Instant heartbeatCutoff) {
        return jdbc.query("""
                select v.node_id,v.process_instance_id,v.provider_id,v.status,v.failure_code,v.checked_at
                from processing_storage_node_validations v
                join processing_storage_nodes n on n.node_id=v.node_id and n.process_instance_id=v.process_instance_id
                where v.provider_id=? and n.heartbeat_at>=? and v.checked_at>=?
                order by v.node_id,v.process_instance_id
                """, this::validation, providerId, sqlTimestamp(heartbeatCutoff), sqlTimestamp(heartbeatCutoff));
    }

    @Override
    public long countActiveNodes(Instant heartbeatCutoff) {
        Long count = jdbc.queryForObject("select count(*) from processing_storage_nodes where heartbeat_at>=?", Long.class, sqlTimestamp(heartbeatCutoff));
        return count == null ? 0L : count;
    }

    @Override
    public boolean providerHasArtifactReferences(UUID providerId) {
        Integer artifacts = jdbc.queryForObject(
                "select count(*) from artifacts where storage_provider=? and status<>'DELETED'", Integer.class, providerId.toString());
        Integer pins = jdbc.queryForObject(
                "select count(*) from processing_storage_cycle_pins where provider_id=?", Integer.class, providerId);
        return (artifacts != null && artifacts > 0) || (pins != null && pins > 0);
    }

    @Override
    public int claimLegacyLocalArtifacts(UUID providerId, String absoluteRootPath) {
        return jdbc.update("""
                update artifacts set storage_provider=?
                where storage_provider='local' and storage_bucket=?
                """, providerId.toString(), absoluteRootPath);
    }

    private Optional<ProcessingStorageAssignment> assignmentByScope(String scope) {
        List<ProcessingStorageAssignment> rows = jdbc.query("""
                select id,tenant_id,provider_id,activated_by,activated_at
                from processing_storage_assignments where scope_key=?
                """, this::assignment, scope);
        return rows.stream().findFirst();
    }

    private void upsertAssignment(String scopeKey, UUID tenantId, UUID providerId, UUID actorId, Instant now) {
        jdbc.update("""
                insert into processing_storage_assignments(id,scope_key,tenant_id,provider_id,activated_by,activated_at)
                values (?,?,?,?,?,?)
                on conflict (scope_key) do update set
                    tenant_id=excluded.tenant_id,provider_id=excluded.provider_id,
                    activated_by=excluded.activated_by,activated_at=excluded.activated_at
                """, UUID.randomUUID(), scopeKey, tenantId, providerId, actorId, sqlTimestamp(now));
    }

    private ProcessingStorageProvider provider(ResultSet rs, int rowNum) throws SQLException {
        return new ProcessingStorageProvider(
                rs.getObject("id", UUID.class), rs.getString("name"),
                ProcessingStorageProviderType.valueOf(rs.getString("provider_type")), rs.getString("root_path"),
                ProcessingStorageProviderStatus.valueOf(rs.getString("status")), rs.getInt("configuration_version"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private ProcessingStorageAssignment assignment(ResultSet rs, int rowNum) throws SQLException {
        UUID tenant = rs.getObject("tenant_id", UUID.class);
        return new ProcessingStorageAssignment(rs.getObject("id", UUID.class), tenant == null ? null : TenantId.of(tenant),
                rs.getObject("provider_id", UUID.class), rs.getObject("activated_by", UUID.class),
                rs.getTimestamp("activated_at").toInstant());
    }

    private ProcessingStorageNodeValidation validation(ResultSet rs, int rowNum) throws SQLException {
        return new ProcessingStorageNodeValidation(rs.getString("node_id"), rs.getObject("process_instance_id", UUID.class),
                rs.getObject("provider_id", UUID.class), ProcessingStorageValidationStatus.valueOf(rs.getString("status")),
                rs.getString("failure_code"), rs.getTimestamp("checked_at").toInstant());
    }
    private static Timestamp sqlTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

}
