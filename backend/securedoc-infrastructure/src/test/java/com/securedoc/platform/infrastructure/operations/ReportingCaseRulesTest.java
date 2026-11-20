package com.securedoc.platform.infrastructure.operations;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U024 step 0: Reports' "No review needed", "Handled without a person" and the compliance report's "Finished by hand"
 * share one definition. The numbers on real data are checked by test-tools/compliance-report (casesNoPerson equals
 * "No review needed" on a period with an unreviewed case finished by hand).
 */
class ReportingCaseRulesTest {

    private static final String ACTION = "BUSINESS_MANUAL_FALLBACK_SUBMITTED";

    @Test
    void noPersonMeansNotReviewedAndNotFinishedByHand() {
        assertEquals("(not f.reviewed_flag and not " + ReportingCaseRules.FINISHED_BY_HAND + ")", ReportingCaseRules.NO_PERSON);
        assertTrue(ReportingCaseRules.FINISHED_BY_HAND.contains("a.action='" + ACTION + "'"));
        assertTrue(ReportingCaseRules.FINISHED_BY_HAND.contains("a.outcome='SUCCESS'"));
        assertTrue(ReportingCaseRules.FINISHED_BY_HAND.contains("a.tenant_id=f.tenant_id"));
    }

    @Test
    void theTimeSavedQueryUsesTheSharedRuleOnce() {
        String sql = JpaOperationsReadAdapter.TIME_SAVED_CASES_SQL;
        assertTrue(sql.contains("f.document_count, " + ReportingCaseRules.NO_PERSON + " as no_person"), sql);
        assertTrue(!sql.contains("{NO_PERSON}"), sql);
        assertEquals(1, occurrences(sql, ACTION), "one copy of the finished-by-hand rule");
    }

    @Test
    void theComplianceFinishedByHandCountUsesTheSharedRule() throws Exception {
        Class<?> adapter = Class.forName("com.securedoc.platform.infrastructure.compliance.JpaComplianceReportReadAdapter");
        Field field = adapter.getDeclaredField("FINISHED_BY_HAND_SQL");
        field.setAccessible(true);
        String sql = (String) field.get(null);
        assertTrue(sql.endsWith(" and " + ReportingCaseRules.FINISHED_BY_HAND), sql);
        assertTrue(!sql.contains("andexists"), sql);
        assertEquals(1, occurrences(sql, ACTION));
    }

    private static int occurrences(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) n++;
        return n;
    }
}
