package com.securedoc.platform.infrastructure.operations;

/**
 * The one definition of how a delivered case was handled, shared by Reports ("No review needed", its target bar and
 * Service levels), "What LexaVault did for you" ("Handled without a person") and the compliance report ("No review
 * needed", "Finished by hand"). Both are SQL conditions on a {@code reporting_package_facts} row aliased {@code f}.
 *
 * <p>U024 step 0 (owner's answer 2 to task 0020): "No review needed" leaves out cases finished by hand, so it always
 * equals {@code casesNoPerson}.
 */
public final class ReportingCaseRules {

    /** The case has a successful {@code BUSINESS_MANUAL_FALLBACK_SUBMITTED} record: it was finished by hand. */
    public static final String FINISHED_BY_HAND = """
            exists (select 1 from audit_records a
                     where a.tenant_id=f.tenant_id and a.action='BUSINESS_MANUAL_FALLBACK_SUBMITTED'
                       and a.outcome='SUCCESS' and a.resource_type='DocumentPackage'
                       and a.resource_id=cast(f.package_id as text))""";

    /** No person reviewed the case, and it was not finished by hand. */
    public static final String NO_PERSON = "(not f.reviewed_flag and not " + FINISHED_BY_HAND + ")";

    private ReportingCaseRules() {
    }
}
