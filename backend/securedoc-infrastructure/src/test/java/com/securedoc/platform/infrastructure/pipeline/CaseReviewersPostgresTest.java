package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.audit.JdbcCaseReviewers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Task 0076: who reviewed a case, from the audit trail. */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class CaseReviewersPostgresTest {

    @Test
    void everyReviewDecisionCountsAndNothingElseDoes() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("reviewers")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            JdbcCaseReviewers reviewers = new JdbcCaseReviewers(jdbc);
            TenantId tenant = TenantId.generate();
            TestRows.tenant(jdbc, tenant);
            TenantId otherTenant = TenantId.generate();
            TestRows.tenant(jdbc, otherTenant);
            ActorId me = ActorId.generate();
            ActorId someoneElse = ActorId.generate();

            for (String action : List.of("FINDING_CONFIRMED", "FINDING_DISMISSED", "REVIEW_PAGE_CHECKED",
                    "BUSINESS_MANUAL_FALLBACK_REGION_ADDED", "BUSINESS_MANUAL_FALLBACK_SUBMITTED")) {
                PackageId reviewed = PackageId.generate();
                PackageId asResource = PackageId.generate();
                record(jdbc, tenant, me, action, "SUCCESS", "Finding", UUID.randomUUID().toString(), reviewed.toString());
                record(jdbc, tenant, me, action, "SUCCESS", "DocumentPackage", asResource.toString(), null);
                assertEquals(Set.of(reviewed, asResource), reviewers.reviewedBy(tenant, me,
                        List.of(reviewed, asResource, PackageId.generate())), action);
                assertEquals(Set.of(), reviewers.reviewedBy(tenant, someoneElse, List.of(reviewed)), "another person");
                assertEquals(Set.of(), reviewers.reviewedBy(otherTenant, me, List.of(reviewed)), "another entity");
            }
            PackageId notReviewed = PackageId.generate();
            record(jdbc, tenant, me, "PACKAGE_FINAL_APPROVED", "SUCCESS", "DocumentPackage", notReviewed.toString(), notReviewed.toString());
            record(jdbc, tenant, me, "REVIEW_PAGE_PREVIEWED", "SUCCESS", "Document", "d", notReviewed.toString());
            record(jdbc, tenant, me, "FINDING_CONFIRMED", "FAILURE", "Finding", "f", notReviewed.toString());
            assertEquals(Set.of(), reviewers.reviewedBy(tenant, me, List.of(notReviewed)));
            assertEquals(Set.of(), reviewers.reviewedBy(tenant, me, List.of()));
        }
    }

    private static void record(NamedParameterJdbcTemplate jdbc, TenantId tenant, ActorId actor, String action,
                               String outcome, String type, String resource, String caseId) {
        jdbc.update("""
                insert into audit_records (id, tenant_id, actor_id, action, resource_type, resource_id, outcome,
                    correlation_id, detail, occurred_at, created_at, case_id)
                values (gen_random_uuid(), :t, :a, :action, :type, :resource, :outcome, gen_random_uuid(), 'x', now(), now(), :case)
                """, new MapSqlParameterSource("t", tenant.value()).addValue("a", actor.value()).addValue("action", action)
                .addValue("type", type).addValue("resource", resource).addValue("outcome", outcome).addValue("case", caseId));
    }
}
