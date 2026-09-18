package com.securedoc.platform.infrastructure.licensing;

import com.securedoc.platform.application.licensing.TenantLicenseRepository;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.licensing.TenantLicenseRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JdbcTenantLicenseRepositoryAdapter implements TenantLicenseRepository {
    private final JdbcTemplate jdbc;

    public JdbcTenantLicenseRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public TenantLicenseRecord save(TenantLicenseRecord record) {
        jdbc.update("""
                INSERT INTO tenant_license_records
                    (id, tenant_id, license_id, signed_token, payload_sha256, installed_by, installed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, license_id) DO NOTHING
                """,
                record.id(), record.tenantId().value(), record.licenseId(), record.signedToken(),
                record.payloadSha256(), record.installedBy(), Timestamp.from(record.installedAt()));
        return jdbc.query("""
                SELECT id, tenant_id, license_id, signed_token, payload_sha256, installed_by, installed_at
                FROM tenant_license_records WHERE tenant_id = ? AND license_id = ?
                """, this::map, record.tenantId().value(), record.licenseId()).stream().findFirst().orElse(record);
    }

    @Override
    @Transactional
    public void activate(TenantId tenantId, UUID licenseRecordId, UUID actorId) {
        jdbc.update("""
                INSERT INTO tenant_license_assignments (tenant_id, license_record_id, activated_by, activated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE
                   SET license_record_id = EXCLUDED.license_record_id,
                       activated_by = EXCLUDED.activated_by,
                       activated_at = EXCLUDED.activated_at
                """, tenantId.value(), licenseRecordId, actorId, Timestamp.from(Instant.now()));
    }

    @Override
    public Optional<TenantLicenseRecord> findActive(TenantId tenantId) {
        List<TenantLicenseRecord> rows = jdbc.query("""
                SELECT r.id, r.tenant_id, r.license_id, r.signed_token, r.payload_sha256, r.installed_by, r.installed_at
                FROM tenant_license_assignments a
                JOIN tenant_license_records r ON r.id = a.license_record_id
                WHERE a.tenant_id = ?
                """, this::map, tenantId.value());
        return rows.stream().findFirst();
    }

    @Override
    public List<TenantLicenseRecord> findHistory(TenantId tenantId) {
        return jdbc.query("""
                SELECT id, tenant_id, license_id, signed_token, payload_sha256, installed_by, installed_at
                FROM tenant_license_records
                WHERE tenant_id = ?
                ORDER BY installed_at DESC
                """, this::map, tenantId.value());
    }

    private TenantLicenseRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new TenantLicenseRecord(
                rs.getObject("id", UUID.class),
                TenantId.of(rs.getObject("tenant_id", UUID.class)),
                rs.getObject("license_id", UUID.class),
                rs.getString("signed_token"),
                rs.getString("payload_sha256"),
                rs.getObject("installed_by", UUID.class),
                rs.getTimestamp("installed_at").toInstant());
    }
}
