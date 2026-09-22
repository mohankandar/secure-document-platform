package com.securedoc.platform.infrastructure.branding;

import com.securedoc.platform.application.branding.BrandingConfiguration;
import com.securedoc.platform.application.branding.BrandingConfigurationRepository;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JdbcBrandingConfigurationRepositoryAdapter implements BrandingConfigurationRepository {
    private final JdbcTemplate jdbc;

    public JdbcBrandingConfigurationRepositoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BrandingConfiguration> findDeployment() {
        return jdbc.query("""
                SELECT * FROM branding_configurations WHERE tenant_id IS NULL
                """, this::map).stream().findFirst();
    }

    @Override
    public Optional<BrandingConfiguration> findTenant(TenantId tenantId) {
        return jdbc.query("SELECT * FROM branding_configurations WHERE tenant_id = ?", this::map,
                tenantId.value()).stream().findFirst();
    }

    @Override
    public List<BrandingConfiguration> findTenantOverrides() {
        return jdbc.query("SELECT * FROM branding_configurations WHERE tenant_id IS NOT NULL ORDER BY updated_at DESC", this::map);
    }

    @Override
    public BrandingConfiguration save(BrandingConfiguration c) {
        jdbc.update("""
                INSERT INTO branding_configurations
                    (id, tenant_id, application_name, short_name, organization_name, page_title,
                     accent_color, secondary_color, primary_logo, primary_logo_media_type,
                     compact_logo, compact_logo_media_type, favicon, favicon_media_type, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    application_name = EXCLUDED.application_name,
                    short_name = EXCLUDED.short_name,
                    organization_name = EXCLUDED.organization_name,
                    page_title = EXCLUDED.page_title,
                    accent_color = EXCLUDED.accent_color,
                    secondary_color = EXCLUDED.secondary_color,
                    primary_logo = EXCLUDED.primary_logo,
                    primary_logo_media_type = EXCLUDED.primary_logo_media_type,
                    compact_logo = EXCLUDED.compact_logo,
                    compact_logo_media_type = EXCLUDED.compact_logo_media_type,
                    favicon = EXCLUDED.favicon,
                    favicon_media_type = EXCLUDED.favicon_media_type,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at
                """,
                c.id(), c.tenantId() == null ? null : c.tenantId().value(), c.applicationName(), c.shortName(),
                c.organizationName(), c.pageTitle(), c.accentColor(), c.secondaryColor(), c.primaryLogo(),
                c.primaryLogoMediaType(), c.compactLogo(), c.compactLogoMediaType(), c.favicon(), c.faviconMediaType(),
                c.updatedBy(), Timestamp.from(c.updatedAt()));
        return c;
    }

    @Override
    public void deleteTenant(TenantId tenantId) {
        jdbc.update("DELETE FROM branding_configurations WHERE tenant_id = ?", tenantId.value());
    }

    private BrandingConfiguration map(ResultSet rs, int rowNum) throws SQLException {
        UUID tenant = rs.getObject("tenant_id", UUID.class);
        return new BrandingConfiguration(
                rs.getObject("id", UUID.class), tenant == null ? null : TenantId.of(tenant),
                rs.getString("application_name"), rs.getString("short_name"), rs.getString("organization_name"),
                rs.getString("page_title"), rs.getString("accent_color"), rs.getString("secondary_color"),
                rs.getBytes("primary_logo"), rs.getString("primary_logo_media_type"), rs.getBytes("compact_logo"),
                rs.getString("compact_logo_media_type"), rs.getBytes("favicon"), rs.getString("favicon_media_type"),
                rs.getObject("updated_by", UUID.class), rs.getTimestamp("updated_at").toInstant());
    }
}
