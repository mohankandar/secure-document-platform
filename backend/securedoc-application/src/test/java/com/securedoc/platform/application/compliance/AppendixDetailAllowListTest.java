package com.securedoc.platform.application.compliance;

import com.securedoc.platform.domain.audit.AuditAction;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0017 B3: the appendix prints a detail only for actions on the allow list. */
class AppendixDetailAllowListTest {

    /** Actions whose detail can hold a file name, typed name, external text or an exception's message. */
    private static final Set<AuditAction> NEVER = EnumSet.of(
            AuditAction.DOCUMENT_EXPORTED, AuditAction.AUDIT_EXPORTED, AuditAction.POLLING_RUN_FAILED,
            AuditAction.POLLING_SOURCE_ACKNOWLEDGED, AuditAction.INTEGRATION_CONNECTION_CREATED,
            AuditAction.INTEGRATION_CONNECTION_UPDATED, AuditAction.INTEGRATION_CONNECTION_ENABLED,
            AuditAction.INTEGRATION_CONNECTION_DISABLED, AuditAction.ROUTING_RULE_CREATED,
            AuditAction.ROUTING_RULE_UPDATED, AuditAction.DERIVED_DOCUMENT_CREATED,
            AuditAction.DERIVED_DOCUMENT_DELIVERED, AuditAction.LICENSE_INSTALLED);

    @Test
    void unsafeActionsAreNeverOnTheList() {
        for (AuditAction action : NEVER) {
            assertFalse(AppendixDetailAllowList.isAllowed(action.name()), action.name());
            assertEquals("—", AppendixDetailAllowList.printable(action.name(),
                    "Document exported: destination=LOCAL, filename=MARKER-borrower-file.pdf"));
        }
    }

    @Test
    void theListIsExactlyTheReviewedSet() {
        // Changing the list is a review decision: update the report's list and this count together.
        // 89 in task 0017; task 0018 A2 adds AUTHORIZATION_FAILED (now written by the capability interceptor).
        // Task 0021 adds INTAKE_SENSITIVITY_LEVEL_UNKNOWN (code and scope only) and WATCHED_FOLDER_SETTINGS_CHANGED
        // (level codes only).
        assertEquals(92, AppendixDetailAllowList.allowed().size());
        assertTrue(AppendixDetailAllowList.isAllowed("INTAKE_SENSITIVITY_LEVEL_UNKNOWN"));
        assertTrue(AppendixDetailAllowList.isAllowed("WATCHED_FOLDER_SETTINGS_CHANGED"));
        assertTrue(AppendixDetailAllowList.isAllowed("SENSITIVE_DATA_POLICY_CHANGED"));
        assertTrue(AppendixDetailAllowList.isAllowed("COMPLIANCE_REPORT_CREATED"));
        assertTrue(AppendixDetailAllowList.isAllowed("AUTHORIZATION_FAILED"));
        assertFalse(AppendixDetailAllowList.isAllowed("NOT_AN_ACTION"));
        assertFalse(AppendixDetailAllowList.isAllowed(null));
        // Actions nothing writes today stay off until someone reviews what they will hold.
        assertFalse(AppendixDetailAllowList.isAllowed("PACKAGE_REJECTED"));
    }

    @Test
    void printedDetailIsCleanedAndCut() {
        assertEquals("Detection completed: findings=3", AppendixDetailAllowList.printable(
                "DOCUMENT_DETECTION_COMPLETED", " Detection completed:\nfindings=3 ").replace("  ", " "));
        assertEquals("—", AppendixDetailAllowList.printable("PACKAGE_CREATED", null));
        String longDetail = "x".repeat(500);
        assertEquals(301, AppendixDetailAllowList.printable("PACKAGE_CREATED", longDetail).length());
    }
}
