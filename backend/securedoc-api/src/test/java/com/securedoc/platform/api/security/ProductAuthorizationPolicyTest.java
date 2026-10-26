package com.securedoc.platform.api.security;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProductAuthorizationPolicyTest {
    private final ProductAuthorizationPolicy policy = new ProductAuthorizationPolicy();

    @Test
    void documentProcessorGetsOnlyBaseBusinessWorkflowCapabilities() {
        var caps = policy.capabilitiesFor(Set.of("DOCUMENT_PROCESSOR"));
        assertEquals(EnumSet.of(
                UserCapability.DASHBOARD_ACCESS,
                UserCapability.DOCUMENT_INTAKE,
                UserCapability.MANUAL_REVIEW,
                UserCapability.FINDING_DECISION,
                UserCapability.REDACTION_ADJUST,
                UserCapability.REVIEW_SUBMIT), caps);
        assertFalse(caps.contains(UserCapability.PACKAGE_APPROVE_DELIVERY));
        assertFalse(caps.contains(UserCapability.PACKAGE_REPROCESS));
        assertFalse(caps.contains(UserCapability.PACKAGE_REMOVE_FROM_DASHBOARD));
    }

    @Test
    void documentSupervisorAddsOnlyLifecycleSupervisionCapabilities() {
        var caps = policy.capabilitiesFor(Set.of("DOCUMENT_SUPERVISOR"));
        assertTrue(caps.containsAll(policy.capabilitiesFor(Set.of("DOCUMENT_PROCESSOR"))));
        assertTrue(caps.contains(UserCapability.PACKAGE_APPROVE_DELIVERY));
        assertTrue(caps.contains(UserCapability.PACKAGE_REPROCESS));
        assertTrue(caps.contains(UserCapability.PACKAGE_REMOVE_FROM_DASHBOARD));
        assertFalse(caps.contains(UserCapability.REPORT_EXECUTIVE_VIEW));
        assertFalse(caps.contains(UserCapability.TECHNICAL_RETRY));
    }

    @Test
    void businessAdminHasBusinessConfigurationAndReportsWithoutDocumentOperations() {
        var caps = policy.capabilitiesFor(Set.of("BUSINESS_ADMIN"));
        assertFalse(caps.contains(UserCapability.DASHBOARD_ACCESS));
        assertFalse(caps.contains(UserCapability.DOCUMENT_INTAKE));
        assertFalse(caps.contains(UserCapability.MANUAL_REVIEW));
        assertFalse(caps.contains(UserCapability.PACKAGE_APPROVE_DELIVERY));
        assertFalse(caps.contains(UserCapability.PACKAGE_REPROCESS));
        assertFalse(caps.contains(UserCapability.PACKAGE_REMOVE_FROM_DASHBOARD));
        assertTrue(caps.contains(UserCapability.TENANT_POLICY_MANAGE));
        assertTrue(caps.contains(UserCapability.TENANT_IDENTIFIER_MANAGE));
        assertTrue(caps.contains(UserCapability.REPORT_EXECUTIVE_VIEW));
        assertTrue(caps.contains(UserCapability.REPORT_OPERATIONAL_VIEW));
        assertTrue(caps.contains(UserCapability.REPORT_EXPORT));
        assertFalse(caps.contains(UserCapability.TENANT_INTEGRATION_MANAGE));
        assertFalse(caps.contains(UserCapability.TECHNICAL_EXCEPTION_VIEW));
        assertFalse(caps.contains(UserCapability.TECHNICAL_RETRY));
        assertFalse(caps.contains(UserCapability.AUDIT_VIEW));
        assertFalse(caps.contains(UserCapability.PLATFORM_DIAGNOSTICS));
    }

    @Test
    void combinedBusinessAdminAndProcessorPreservesAdditiveCapabilitiesWithoutApproval() {
        var caps = policy.capabilitiesFor(Set.of("BUSINESS_ADMIN", "DOCUMENT_PROCESSOR"));
        assertTrue(caps.contains(UserCapability.TENANT_POLICY_MANAGE));
        assertTrue(caps.contains(UserCapability.REPORT_EXECUTIVE_VIEW));
        assertTrue(caps.contains(UserCapability.DOCUMENT_INTAKE));
        assertTrue(caps.contains(UserCapability.MANUAL_REVIEW));
        assertFalse(caps.contains(UserCapability.PACKAGE_APPROVE_DELIVERY));
    }

    @Test
    void combinedBusinessAdminAndSupervisorPreservesApproval() {
        var caps = policy.capabilitiesFor(Set.of("BUSINESS_ADMIN", "DOCUMENT_SUPERVISOR"));
        assertTrue(caps.contains(UserCapability.TENANT_POLICY_MANAGE));
        assertTrue(caps.contains(UserCapability.DOCUMENT_INTAKE));
        assertTrue(caps.contains(UserCapability.PACKAGE_APPROVE_DELIVERY));
    }

    @Test
    void reportViewerIsReportsOnlyAndReadOnly() {
        var caps = policy.capabilitiesFor(Set.of("REPORT_VIEWER"));
        assertEquals(EnumSet.of(UserCapability.REPORT_EXECUTIVE_VIEW), caps);
        assertFalse(caps.contains(UserCapability.DASHBOARD_ACCESS));
        assertFalse(caps.contains(UserCapability.DOCUMENT_INTAKE));
        assertFalse(caps.contains(UserCapability.REPORT_EXPORT));
        assertFalse(caps.contains(UserCapability.TECHNICAL_EXCEPTION_VIEW));
    }

    @Test
    void tenantItAdminGetsTechnicalOperationsReportsAndDashboardButNoBusinessWorkflow() {
        var caps = policy.capabilitiesFor(Set.of("TENANT_IT_ADMIN"));
        assertTrue(caps.contains(UserCapability.DASHBOARD_ACCESS));
        assertTrue(caps.contains(UserCapability.REPORT_EXECUTIVE_VIEW));
        assertTrue(caps.contains(UserCapability.REPORT_OPERATIONAL_VIEW));
        assertTrue(caps.contains(UserCapability.REPORT_EXPORT));
        assertTrue(caps.contains(UserCapability.TENANT_INTEGRATION_MANAGE));
        assertTrue(caps.contains(UserCapability.TENANT_AUTH_MANAGE));
        assertTrue(caps.contains(UserCapability.TECHNICAL_EXCEPTION_VIEW));
        assertTrue(caps.contains(UserCapability.TECHNICAL_RETRY));
        assertTrue(caps.contains(UserCapability.TECHNICAL_HOLD_MANAGE));
        assertTrue(caps.contains(UserCapability.AUDIT_VIEW));
        assertFalse(caps.contains(UserCapability.DOCUMENT_INTAKE));
        assertFalse(caps.contains(UserCapability.MANUAL_REVIEW));
        assertFalse(caps.contains(UserCapability.PACKAGE_APPROVE_DELIVERY));
        assertFalse(caps.contains(UserCapability.PACKAGE_REPROCESS));
        assertFalse(caps.contains(UserCapability.PACKAGE_REMOVE_FROM_DASHBOARD));
        assertFalse(caps.contains(UserCapability.TENANT_POLICY_MANAGE));
    }

    @Test
    void platformOperationsAddsDeepDiagnosticsWithoutBusinessWorkflow() {
        var caps = policy.capabilitiesFor(Set.of("PLATFORM_OPERATIONS_ADMIN"));
        assertTrue(caps.contains(UserCapability.PLATFORM_DIAGNOSTICS));
        assertTrue(caps.contains(UserCapability.PLATFORM_OPERATIONS));
        assertTrue(caps.contains(UserCapability.TECHNICAL_ADMIN_VIEW));
        assertTrue(caps.contains(UserCapability.REPORT_EXPORT));
        assertFalse(caps.contains(UserCapability.DOCUMENT_INTAKE));
        assertFalse(caps.contains(UserCapability.MANUAL_REVIEW));
        assertFalse(caps.contains(UserCapability.PACKAGE_APPROVE_DELIVERY));
        assertFalse(caps.contains(UserCapability.TENANT_POLICY_MANAGE));
        assertFalse(caps.contains(UserCapability.TENANT_AUTH_MANAGE));
    }

    @Test
    void adminIsExplicitAndReceivesAllCapabilities() {
        assertEquals(EnumSet.allOf(UserCapability.class), policy.capabilitiesFor(Set.of("ADMIN")));
        assertTrue(policy.capabilitiesFor(Set.of()).isEmpty());
        assertTrue(policy.capabilitiesFor(Set.of("UNKNOWN")).isEmpty());
    }

    @Test
    void legacyNamesDoNotActAsAuthorizationRoles() {
        assertTrue(policy.capabilitiesFor(Set.of("USER")).isEmpty());
        assertTrue(policy.capabilitiesFor(Set.of("REVIEWER")).isEmpty());
        assertTrue(policy.capabilitiesFor(Set.of("APPROVER")).isEmpty());
    }
}
