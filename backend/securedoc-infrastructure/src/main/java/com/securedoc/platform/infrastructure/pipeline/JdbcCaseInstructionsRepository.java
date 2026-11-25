package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.application.port.out.CaseInstructionsRepository;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Table case_instructions (migration 085). The row goes with the case's files (StoredFileErasure). */
@Repository
public class JdbcCaseInstructionsRepository implements CaseInstructionsRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCaseInstructionsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Stored> find(TenantId tenantId, PackageId packageId) {
        return jdbc.query("SELECT file_id, storage_provider, storage_bucket, storage_key FROM case_instructions"
                        + " WHERE tenant_id = :tenantId AND package_id = :packageId", params(tenantId, packageId),
                (rs, n) -> new Stored(rs.getObject(1, UUID.class),
                        ArtifactStorageReference.of(rs.getString(2), rs.getString(3), rs.getString(4)))).stream().findFirst();
    }

    @Override
    public void save(TenantId tenantId, PackageId packageId, Stored stored) {
        jdbc.update("INSERT INTO case_instructions (package_id, tenant_id, file_id, storage_provider, storage_bucket,"
                + " storage_key, changed_at) VALUES (:packageId, :tenantId, :file, :provider, :bucket, :key, clock_timestamp())"
                + " ON CONFLICT (package_id) DO UPDATE SET file_id = excluded.file_id,"
                + " storage_provider = excluded.storage_provider, storage_bucket = excluded.storage_bucket,"
                + " storage_key = excluded.storage_key, changed_at = excluded.changed_at"
                + " WHERE case_instructions.tenant_id = excluded.tenant_id", params(tenantId, packageId)
                .addValue("file", stored.fileId()).addValue("provider", stored.reference().storageProvider())
                .addValue("bucket", stored.reference().bucket()).addValue("key", stored.reference().key()));
    }

    @Override
    public void remove(TenantId tenantId, PackageId packageId) {
        jdbc.update("DELETE FROM case_instructions WHERE tenant_id = :tenantId AND package_id = :packageId",
                params(tenantId, packageId));
    }

    private static MapSqlParameterSource params(TenantId tenantId, PackageId packageId) {
        return new MapSqlParameterSource("tenantId", tenantId.value()).addValue("packageId", packageId.value());
    }
}
