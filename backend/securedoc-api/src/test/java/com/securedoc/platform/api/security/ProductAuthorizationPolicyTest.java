package com.securedoc.platform.api.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProductAuthorizationPolicyTest {
    private final ProductAuthorizationPolicy policy = new ProductAuthorizationPolicy();

    @Test
    void businessAdminDoesNotInheritReviewOrReprocess() {
        var caps = policy.capabilitiesFor(Set.of("BUSINESS_ADMIN"));
        assertTrue(caps.contains(UserCapability.TENANT_POLICY_MANAGE));
        assertFalse(caps.contains(UserCapability.MANUAL_REVIEW));
        assertFalse(caps.contains(UserCapability.PACKAGE_REPROCESS));
        assertFalse(caps.contains(UserCapability.TECHNICAL_ADMIN_VIEW));
        assertTrue(caps.contains(UserCapability.REPORT_EXECUTIVE_VIEW));
        assertFalse(caps.contains(UserCapability.REPORT_OPERATIONAL_VIEW));
        assertFalse(caps.contains(UserCapability.REPORT_EXPORT));
    }

    @Test
    void reviewerAndApproverHaveExplicitWorkflowCapabilities() {
        assertTrue(policy.capabilitiesFor(Set.of("REVIEWER")).contains(UserCapability.MANUAL_REVIEW));
        var approver = policy.capabilitiesFor(Set.of("APPROVER"));
        assertTrue(approver.contains(UserCapability.MANUAL_REVIEW));
        assertTrue(approver.contains(UserCapability.PACKAGE_REPROCESS));
        assertTrue(approver.contains(UserCapability.PACKAGE_REMOVE_FROM_DASHBOARD));
    }

    @Test
    void technicalAdminRolesAreDistinctFromBusinessAdmin() {
        var tenantIt = policy.capabilitiesFor(Set.of("TENANT_IT_ADMIN"));
        assertTrue(tenantIt.contains(UserCapability.TECHNICAL_ADMIN_VIEW));
        assertTrue(tenantIt.contains(UserCapability.TECHNICAL_RETRY));
        assertFalse(tenantIt.contains(UserCapability.TENANT_POLICY_MANAGE));
        assertFalse(tenantIt.contains(UserCapability.PLATFORM_OPERATIONS));

        var platformOps = policy.capabilitiesFor(Set.of("PLATFORM_OPERATIONS_ADMIN"));
        assertTrue(platformOps.contains(UserCapability.PLATFORM_OPERATIONS));
        assertTrue(platformOps.contains(UserCapability.TECHNICAL_ADMIN_VIEW));
        assertTrue(platformOps.contains(UserCapability.REPORT_EXECUTIVE_VIEW));
        assertTrue(platformOps.contains(UserCapability.REPORT_OPERATIONAL_VIEW));
        assertTrue(platformOps.contains(UserCapability.REPORT_EXPORT));
    }

    @Test
    void reportViewerHasReadOnlyExecutiveReportingAndExport() {
        var caps = policy.capabilitiesFor(Set.of("REPORT_VIEWER"));
        assertEquals(Set.of(UserCapability.REPORT_EXECUTIVE_VIEW, UserCapability.REPORT_EXPORT), caps);
        assertFalse(caps.contains(UserCapability.TENANT_POLICY_MANAGE));
        assertFalse(caps.contains(UserCapability.TECHNICAL_ADMIN_VIEW));
        assertFalse(caps.contains(UserCapability.MANUAL_REVIEW));
    }

    @Test
    void workflowRolesDoNotGainTenantWideReporting() {
        for (String role : Set.of("USER", "REVIEWER", "APPROVER")) {
            var caps = policy.capabilitiesFor(Set.of(role));
            assertFalse(caps.contains(UserCapability.REPORT_EXECUTIVE_VIEW), role);
            assertFalse(caps.contains(UserCapability.REPORT_OPERATIONAL_VIEW), role);
            assertFalse(caps.contains(UserCapability.REPORT_EXPORT), role);
        }
    }
}
