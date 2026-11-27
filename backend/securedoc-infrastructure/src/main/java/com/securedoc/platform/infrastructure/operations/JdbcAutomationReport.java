package com.securedoc.platform.infrastructure.operations;

import com.securedoc.platform.application.port.out.AutomationReportPort;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Task 0105: counts of one entity's redacted findings by who placed them, from the findings and, for a finding saved
 * before task 0105 (no placement recorded), its audit records. Counts only; no value, text or person is read.
 */
@Repository
public class JdbcAutomationReport implements AutomationReportPort {

    /** Drawn by a person: LexaVault missed it (a manual region, a box drawn on a page checked by hand). */
    private static final String DRAWN = "(f.reader = 'PERSON' or (f.reader is null"
            + " and f.detector_name in ('MANUAL_FALLBACK', 'review-page-check')))";

    /** Found by LexaVault, placed by a person. Before task 0105, a box change in the audit. */
    private static final String PLACED = "(f.placement = 'PERSON' or (f.placement is null and exists ("
            + "select 1 from audit_records a where a.tenant_id = f.tenant_id and a.resource_id = f.id::text"
            + " and a.action = 'FINDING_BOUNDING_BOX_UPDATED')))";

    private static final String COUNTS = """
            select count(*) filter (where not %1$s and not %2$s) as automatic,
                   count(*) filter (where not %1$s and %2$s) as placed,
                   count(*) filter (where %1$s) as drawn,
                   count(*) filter (where f.found_by like '%%TEXT%%') as by_text,
                   count(*) filter (where f.found_by like '%%VISION%%' and f.found_by not like '%%TEXT%%') as by_vision
              from findings f
             where f.tenant_id = :tenant and f.detected_at >= :from and f.detected_at < :to
               and f.status in ('CONFIRMED', 'REDACTED')
            """.formatted(DRAWN, PLACED);

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAutomationReport(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Counts counts(TenantId entity, Instant from, Instant to) {
        var params = new MapSqlParameterSource().addValue("tenant", entity.value())
                .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to));
        return jdbc.queryForObject(COUNTS, params, (rs, n) -> new Counts(rs.getLong("automatic"), rs.getLong("placed"),
                rs.getLong("drawn"), rs.getLong("by_text"), rs.getLong("by_vision")));
    }
}
