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

/** Tenant-scoped read of each package's main ID, through the same BusinessRecord join the Support search uses. */
@Repository
public class JdbcCasePrimaryIdentifierLookup implements CasePrimaryIdentifierLookup {
    private static final String SQL = """
            SELECT dp.id AS package_id, bid.label AS primary_label, bri.identifier_value AS primary_value
            FROM document_packages dp
            JOIN business_identifier_definitions bid
              ON bid.tenant_id = dp.tenant_id
             AND bid.primary_flag = true
             AND bid.active_flag = true
            JOIN business_record_identifiers bri
              ON bri.business_record_id = dp.business_record_id
             AND bri.tenant_id = dp.tenant_id
             AND bri.definition_id = bid.id
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
                result.put(PackageId.of(rs.getObject("package_id", UUID.class)), new CasePrimaryIdentifier(rs.getString("primary_label"), value));
            }
        });
        return result;
    }
}
