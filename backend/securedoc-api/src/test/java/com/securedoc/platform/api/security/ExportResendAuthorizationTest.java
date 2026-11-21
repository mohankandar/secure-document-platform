package com.securedoc.platform.api.security;

import com.securedoc.platform.api.controller.PackageWorkflowController;
import com.securedoc.platform.api.pipeline.ExportJobController;
import com.securedoc.platform.api.pipeline.ExportRetentionController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0028: the Processor (and the Supervisor) resend exports waiting for a person; approving delivery stays
 * Supervisor only. The retention per destination is the Business Admin's; the grouped IT retry is IT's.
 */
class ExportResendAuthorizationTest {
    private final ProductAuthorizationPolicy policy = new ProductAuthorizationPolicy();

    @Test
    void theProcessorCanResendButCanNotApproveDelivery() {
        var processor = policy.capabilitiesFor(Set.of("DOCUMENT_PROCESSOR"));
        assertTrue(processor.contains(UserCapability.EXPORT_RESEND));
        assertFalse(processor.contains(UserCapability.PACKAGE_APPROVE_DELIVERY));

        var supervisor = policy.capabilitiesFor(Set.of("DOCUMENT_SUPERVISOR"));
        assertTrue(supervisor.contains(UserCapability.EXPORT_RESEND));
        assertTrue(supervisor.contains(UserCapability.PACKAGE_APPROVE_DELIVERY));

        for (String other : List.of("BUSINESS_ADMIN", "REPORT_VIEWER", "TENANT_IT_ADMIN", "PLATFORM_OPERATIONS_ADMIN")) {
            assertFalse(policy.capabilitiesFor(Set.of(other)).contains(UserCapability.EXPORT_RESEND), other);
        }
    }

    @Test
    void theResendEndpointsNeedExportResendAndApprovingDeliveryStillNeedsTheSupervisorsCapability() {
        for (String method : List.of("waitingForYou", "resend", "resendAll")) {
            assertEquals(List.of(UserCapability.EXPORT_RESEND), required(ExportJobController.class, method), method);
        }
        assertEquals(List.of(UserCapability.PACKAGE_APPROVE_DELIVERY),
                required(PackageWorkflowController.class, "finalizePackage"));
        assertEquals(List.of(UserCapability.TECHNICAL_RETRY), required(ExportJobController.class, "retryGroup"));
        assertEquals(List.of(UserCapability.TECHNICAL_EXCEPTION_VIEW), required(ExportJobController.class, "needsIt"));
        assertEquals(List.of(UserCapability.TECHNICAL_EXCEPTION_VIEW), required(ExportJobController.class, "alert"));
        assertEquals(List.of(UserCapability.TENANT_POLICY_MANAGE), required(ExportRetentionController.class, "get"));
        assertEquals(List.of(UserCapability.TENANT_POLICY_MANAGE), required(ExportRetentionController.class, "update"));
    }

    private static List<UserCapability> required(Class<?> type, String name) {
        Method method = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst()
                .orElseThrow();
        RequireCapability annotation = method.getAnnotation(RequireCapability.class);
        assertNotNull(annotation, type.getSimpleName() + "." + name);
        return List.of(annotation.value());
    }
}
