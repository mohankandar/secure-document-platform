package com.securedoc.platform.infrastructure.routing;

import com.securedoc.platform.application.port.out.CasePrimaryIdentifierLookup;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tenant-scoped read of each package's main ID, from the case fields of its business record. A sensitive main ID is left out. */
@Repository
public class JdbcCasePrimaryIdentifierLookup implements CasePrimaryIdentifierLookup {
    private static final String SQL = """
            SELECT dp.id AS package_id, d.label AS primary_label, v.display_value AS primary_value
            FROM document_packages dp
            JOIN fld_field_definition d
              ON d.entity_uuid = dp.tenant_id AND d.product_code = 'LEXAVAULT' AND d.primary_flag AND d.active
            JOIN fld_record r
              ON r.entity_uuid = dp.tenant_id AND r.product_code = 'LEXAVAULT' AND r.record_type = 'business-record'
             AND r.record_id = cast(dp.business_record_id as text)
            JOIN fld_value v
              ON v.record_uuid = r.record_uuid AND lower(v.field_key) = lower(d.field_key) AND NOT v.sensitive
            WHERE dp.tenant_id = :tenantId
              AND dp.id IN (:packageIds)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCasePrimaryIdentifierLookup(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<PackageId, CasePrimaryIdentifier> findByPackageIds(TenantId tenantId, Collection<PackageId> packageIds) {
        if (packageIds == null || packageIds.isEmpty()) return Map.of();
        List<UUID> ids = packageIds.stream().map(PackageId::value).distinct().toList();
        Map<PackageId, CasePrimaryIdentifier> result = new HashMap<>();
        jdbc.query(SQL, new MapSqlParameterSource().addValue("tenantId", tenantId.value()).addValue("packageIds", ids), rs -> {
            String value = rs.getString("primary_value");
            if (value != null && !value.isBlank()) {
                result.put(PackageId.of(rs.getObject("package_id", UUID.class)), new CasePrimaryIdentifier(rs.getString("primary_label"),
                        com.securedoc.platform.application.casefields.CaseFieldText.shown(value)));
            }
        });
        return result;
    }
}
