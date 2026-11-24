package com.securedoc.platform.infrastructure.audit;

import com.securedoc.platform.application.port.out.CaseReviewersPort;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Read from the audit trail: the review decisions a person made, by case. */
@Component
public class JdbcCaseReviewers implements CaseReviewersPort {
    private static final List<String> DECISIONS = List.of("FINDING_CONFIRMED", "FINDING_DISMISSED", "FINDING_OVERRIDDEN",
            "FINDING_BOUNDING_BOX_UPDATED", "REVIEW_PAGE_CHECKED", "REVIEW_APPROVED", "BUSINESS_MANUAL_FALLBACK_STARTED",
            "BUSINESS_MANUAL_FALLBACK_REGION_ADDED", "BUSINESS_MANUAL_FALLBACK_REGION_REMOVED",
            "BUSINESS_MANUAL_FALLBACK_SUBMITTED");

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCaseReviewers(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Set<PackageId> reviewedBy(TenantId tenantId, ActorId person, Collection<PackageId> cases) {
        Set<PackageId> reviewed = new HashSet<>();
        if (cases.isEmpty()) return reviewed;
        List<String> ids = cases.stream().map(PackageId::toString).toList();
        // Older records have no case id of their own; those about the case itself name it as their resource.
        Set<String> found = new HashSet<>(jdbc.queryForList("""
                select distinct coalesce(case_id, resource_id) from audit_records
                 where tenant_id = :tenant and actor_id = :person and outcome = 'SUCCESS' and action in (:actions)
                   and (case_id in (:cases) or (case_id is null and resource_id in (:cases)))
                """, new MapSqlParameterSource("tenant", tenantId.value()).addValue("person", person.value())
                .addValue("actions", DECISIONS).addValue("cases", ids), String.class));
        for (PackageId id : cases) if (found.contains(id.toString())) reviewed.add(id);
        return reviewed;
    }
}
