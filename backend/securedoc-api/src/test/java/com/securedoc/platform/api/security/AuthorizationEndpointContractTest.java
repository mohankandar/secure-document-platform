package com.securedoc.platform.api.security;

import com.securedoc.platform.api.controller.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationEndpointContractTest {

    @Test
    void dashboardAndIntakeApisAreCapabilityProtected() {
        assertCapability(OperationsController.class, "dashboard", UserCapability.DASHBOARD_ACCESS);
        assertCapability(PackageController.class, "createPackage", UserCapability.DOCUMENT_INTAKE);
        assertCapability(PackageController.class, "uploadDocument", UserCapability.DOCUMENT_INTAKE);
        assertCapability(PackageController.class, "submitPackage", UserCapability.DOCUMENT_INTAKE);
        assertCapability(PackageController.class, "getPackageDetail", UserCapability.DASHBOARD_ACCESS);
        assertCapability(PackageController.class, "listPackages", UserCapability.DASHBOARD_ACCESS);
    }

    @Test
    void reviewActionsKeepDistinctCapabilities() {
        assertCapability(FindingReviewController.class, "confirmFinding", UserCapability.FINDING_DECISION);
        assertCapability(FindingReviewController.class, "dismissFinding", UserCapability.FINDING_DECISION);
        assertCapability(FindingReviewController.class, "updateBoundingBox", UserCapability.REDACTION_ADJUST);
        assertCapability(ReviewController.class, "completeTask", UserCapability.REVIEW_SUBMIT);
        assertCapability(PackageLifecycleController.class, "approveDelivery", UserCapability.PACKAGE_APPROVE_DELIVERY);
    }

    @Test
    void supervisorOnlyLifecycleActionsRemainSeparate() {
        assertCapability(PackageLifecycleController.class, "reprocess", UserCapability.PACKAGE_REPROCESS);
        assertCapability(PackageDashboardController.class, "hideFromDashboard", UserCapability.PACKAGE_REMOVE_FROM_DASHBOARD);
    }

    @Test
    void reportReadAndExportRemainSeparateBackendCapabilities() {
        assertCapability(OperationsController.class, "reports", UserCapability.REPORT_EXECUTIVE_VIEW);
        assertCapability(OperationsController.class, "exportReports", UserCapability.REPORT_EXPORT);
    }

    @Test
    void workflowControllersUseCapabilityAnnotationsForProtectedActions() {
        assertCapability(PackageWorkflowController.class, "triggerProcessing", UserCapability.REVIEW_SUBMIT);
        assertCapability(PackageWorkflowController.class, "finalizePackage", UserCapability.PACKAGE_APPROVE_DELIVERY);
    }

    private void assertCapability(Class<?> type, String methodName, UserCapability expected) {
        Method method = Arrays.stream(type.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        RequireCapability annotation = method.getAnnotation(RequireCapability.class);
        assertNotNull(annotation, type.getSimpleName() + "." + methodName);
        assertTrue(Arrays.asList(annotation.value()).contains(expected), type.getSimpleName() + "." + methodName);
    }
}
