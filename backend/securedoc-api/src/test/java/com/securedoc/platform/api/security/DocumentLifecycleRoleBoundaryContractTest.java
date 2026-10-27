package com.securedoc.platform.api.security;

import com.securedoc.platform.api.controller.ArtifactAccessRequestController;
import com.securedoc.platform.api.controller.ArtifactDownloadController;
import com.securedoc.platform.api.controller.PackageLifecycleController;
import com.securedoc.platform.api.controller.VaultEntryController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DocumentLifecycleRoleBoundaryContractTest {
    private final ProductAuthorizationPolicy policy = new ProductAuthorizationPolicy();

    @Test
    void documentLifecycleActionsUseDistinctCapabilities() {
        requires("reprocess", UserCapability.PACKAGE_REPROCESS);
        requires("approveDelivery", UserCapability.PACKAGE_APPROVE_DELIVERY);
        requires("extendTechnicalHold", UserCapability.TECHNICAL_HOLD_MANAGE);
        requires("purgeDue", UserCapability.PLATFORM_OPERATIONS);
        requires("getLifecycle", UserCapability.DASHBOARD_ACCESS);
    }

    @Test
    void standaloneAdministrativeRolesCannotApproveOrReprocessBusinessDocuments() {
        for (String role : Set.of("BUSINESS_ADMIN", "TENANT_IT_ADMIN", "PLATFORM_OPERATIONS_ADMIN", "REPORT_VIEWER", "ADMIN")) {
            var caps = policy.capabilitiesFor(Set.of(role));
            assertFalse(caps.contains(UserCapability.PACKAGE_REPROCESS), role);
            assertFalse(caps.contains(UserCapability.PACKAGE_APPROVE_DELIVERY), role);
            assertFalse(caps.contains(UserCapability.DASHBOARD_ACCESS), role);
        }
    }

    @Test
    void platformCannotManageTenantDocumentHoldAndTenantItCannotPurgeGlobally() {
        assertFalse(policy.capabilitiesFor(Set.of("PLATFORM_OPERATIONS_ADMIN")).contains(UserCapability.TECHNICAL_HOLD_MANAGE));
        assertFalse(policy.capabilitiesFor(Set.of("TENANT_IT_ADMIN")).contains(UserCapability.PLATFORM_OPERATIONS));
        assertTrue(policy.capabilitiesFor(Set.of("TENANT_IT_ADMIN")).contains(UserCapability.TECHNICAL_HOLD_MANAGE));
    }

    @Test
    void legacyVaultAccessControllersAreLocalOnly() {
        for (Class<?> controller : Set.of(ArtifactAccessRequestController.class, VaultEntryController.class)) {
            var local = controller.getAnnotation(ConditionalOnProperty.class);
            assertNotNull(local, controller.getName());
            assertEquals("LOCAL", local.havingValue());
            assertTrue(Arrays.asList(local.name()).contains("securedoc.security.mode"));
        }
        assertNotNull(ArtifactDownloadController.class);
    }

    private static void requires(String methodName, UserCapability expected) {
        Method method = Arrays.stream(PackageLifecycleController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName)).findFirst().orElseThrow();
        var annotation = method.getAnnotation(RequireCapability.class);
        assertNotNull(annotation, methodName);
        assertTrue(Arrays.asList(annotation.value()).contains(expected), methodName);
    }
}
