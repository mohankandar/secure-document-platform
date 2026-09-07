package com.securedoc.platform.infrastructure.support;

import com.securedoc.platform.application.port.out.SupportIncidentSearchRepository;
import com.securedoc.platform.application.support.model.SupportIncidentSearchCriteria;
import com.securedoc.platform.application.support.model.SupportIncidentSearchPage;
import com.securedoc.platform.application.support.model.SupportIncidentSummary;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped read-only support projection. Primary business identity is resolved through the
 * existing BusinessRecord identifier model; no domain-specific identifier is copied into incident storage.
 */
@Repository
public class JdbcSupportIncidentSearchRepository implements SupportIncidentSearchRepository {
    private static final String BASE_FROM = """
            FROM processing_incidents pi
            LEFT JOIN document_packages dp
              ON dp.id = pi.package_id AND dp.tenant_id = pi.tenant_id
            LEFT JOIN business_identifier_definitions bid
              ON bid.tenant_id = pi.tenant_id
             AND bid.primary_flag = true
             AND bid.active_flag = true
            LEFT JOIN business_record_identifiers bri
              ON bri.business_record_id = dp.business_record_id
             AND bri.tenant_id = pi.tenant_id
             AND bri.definition_id = bid.id
            """;

    private static final String SELECT = """
            SELECT pi.id, pi.tenant_id, pi.package_id, pi.document_id,
                   dp.status AS package_status, bid.label AS primary_label, bri.identifier_value AS primary_value,
                   pi.stage, pi.component, pi.reason_code, pi.status, pi.business_outcome,
                   pi.business_continuity_used, pi.retryable, pi.attempt_count, pi.correlation_id,
                   pi.first_observed_at, pi.last_observed_at, pi.resolved_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSupportIncidentSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SupportIncidentSearchPage search(SupportIncidentSearchCriteria criteria) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", criteria.tenantId().value())
                .addValue("limit", criteria.size())
                .addValue("offset", criteria.page() * criteria.size());
        String where = where(criteria, params);

        List<SupportIncidentSummary> items = jdbc.query(
                SELECT + BASE_FROM + where + " ORDER BY pi.last_observed_at DESC LIMIT :limit OFFSET :offset",
                params,
                this::map);

        long total = Optional.ofNullable(jdbc.queryForObject(
                "SELECT COUNT(*) " + BASE_FROM + where,
                params,
                Long.class)).orElse(0L);

        return new SupportIncidentSearchPage(items, total, criteria.page(), criteria.size());
    }

    @Override
    public Optional<SupportIncidentSummary> findByIncidentId(UUID incidentId, TenantId tenantId) {
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("incidentId", incidentId);
        List<SupportIncidentSummary> rows = jdbc.query(
                SELECT + BASE_FROM + " WHERE pi.tenant_id = :tenantId AND pi.id = :incidentId",
                params,
                this::map);
        return rows.stream().findFirst();
    }

    private String where(SupportIncidentSearchCriteria c, MapSqlParameterSource params) {
        List<String> predicates = new ArrayList<>();
        predicates.add("pi.tenant_id = :tenantId");

        if (c.businessIdentifier() != null) {
            predicates.add("(LOWER(COALESCE(bri.identifier_value, '')) LIKE :businessIdentifier OR LOWER(COALESCE(bri.normalized_value, '')) LIKE :businessIdentifier)");
            params.addValue("businessIdentifier", "%" + c.businessIdentifier().toLowerCase() + "%");
        }
        eq(predicates, params, "pi.id", "incidentId", c.incidentId());
        eq(predicates, params, "pi.correlation_id", "correlationId", c.correlationId());
        eq(predicates, params, "pi.package_id", "packageId", c.packageId());
        eq(predicates, params, "pi.document_id", "documentId", c.documentId());
        eq(predicates, params, "pi.stage", "stage", upper(c.stage()));
        eq(predicates, params, "pi.reason_code", "reasonCode", upper(c.reasonCode()));
        eq(predicates, params, "pi.status", "status", upper(c.status()));
        eq(predicates, params, "pi.business_outcome", "businessOutcome", upper(c.businessOutcome()));
        eq(predicates, params, "pi.business_continuity_used", "businessContinuityUsed", c.businessContinuityUsed());
        eq(predicates, params, "pi.retryable", "retryable", c.retryable());

        if (c.from() != null) {
            predicates.add("pi.last_observed_at >= :from");
            params.addValue("from", c.from());
        }
        if (c.to() != null) {
            predicates.add("pi.last_observed_at <= :to");
            params.addValue("to", c.to());
        }

        return " WHERE " + String.join(" AND ", predicates);
    }

    private void eq(List<String> predicates, MapSqlParameterSource params, String column, String name, Object value) {
        if (value == null) return;
        predicates.add(column + " = :" + name);
        params.addValue(name, value);
    }

    private String upper(String value) {
        return value == null ? null : value.toUpperCase();
    }

    private SupportIncidentSummary map(ResultSet rs, int rowNum) throws SQLException {
        String outcome = rs.getString("business_outcome");
        boolean continuity = rs.getBoolean("business_continuity_used");
        String status = rs.getString("status");
        String packageStatus = rs.getString("package_status");
        String impact = ("COMPLETED".equalsIgnoreCase(outcome)
                || "COMPLETED".equalsIgnoreCase(packageStatus))
                ? "COMPLETED"
                : (continuity || "BUSINESS_CONTINUED".equalsIgnoreCase(status) ? "CONTINUED" : "BLOCKED");
        return new SupportIncidentSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("package_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                packageStatus,
                rs.getString("primary_label"),
                rs.getString("primary_value"),
                rs.getString("stage"),
                rs.getString("component"),
                rs.getString("reason_code"),
                status,
                outcome,
                continuity,
                rs.getBoolean("retryable"),
                rs.getInt("attempt_count"),
                rs.getObject("correlation_id", UUID.class),
                impact,
                instant(rs, "first_observed_at"),
                instant(rs, "last_observed_at"),
                instant(rs, "resolved_at"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
