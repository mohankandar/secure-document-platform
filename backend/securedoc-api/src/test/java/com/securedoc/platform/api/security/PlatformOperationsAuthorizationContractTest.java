package com.securedoc.platform.api.security;

import com.securedoc.platform.api.platform.PlatformAdminController;
import com.securedoc.platform.api.controller.ProcessingStorageController;
import com.securedoc.platform.api.controller.ArtifactDownloadController;
import com.securedoc.platform.application.exception.AccessDeniedException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlatformOperationsAuthorizationContractTest {
    private final ProductAuthorizationPolicy policy = new ProductAuthorizationPolicy();

    @Test
    void tenantItOnlyMayTriggerWatchedFolderScan() {
        require(PlatformAdminController.class, "scanWatchedFolder", UserCapability.TENANT_INTEGRATION_MANAGE);
        assertTrue(policy.capabilitiesFor(Set.of("TENANT_IT_ADMIN")).contains(UserCapability.TENANT_INTEGRATION_MANAGE));
        assertFalse(policy.capabilitiesFor(Set.of("PLATFORM_OPERATIONS_ADMIN")).contains(UserCapability.TENANT_INTEGRATION_MANAGE));
    }

    @Test
    void healthChecksAndProcessingStorageChangesRemainPlatformOwned() {
        require(PlatformAdminController.class, "overview", UserCapability.TECHNICAL_ADMIN_VIEW);
        require(PlatformAdminController.class, "healthCheck", UserCapability.PLATFORM_OPERATIONS);
        for (String name : Set.of("overview", "createProvider", "testProvider", "activateDefault",
                "activateTenant", "clearTenantOverride", "retire")) {
            require(ProcessingStorageController.class, name, UserCapability.PLATFORM_OPERATIONS);
        }
        assertFalse(policy.capabilitiesFor(Set.of("TENANT_IT_ADMIN")).contains(UserCapability.PLATFORM_OPERATIONS));
        assertTrue(policy.capabilitiesFor(Set.of("PLATFORM_OPERATIONS_ADMIN")).contains(UserCapability.PLATFORM_OPERATIONS));
    }

    @Test
    void legacyRedactedDownloadIsAlwaysDenied() {
        assertThrows(AccessDeniedException.class,
                () -> new ArtifactDownloadController().downloadRedacted("dummy-document"));
    }

    private static void require(Class<?> type, String methodName, UserCapability capability) {
        Method method = Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst().orElseThrow();
        RequireCapability annotation = method.getAnnotation(RequireCapability.class);
        assertNotNull(annotation, type.getSimpleName() + "." + methodName);
        assertTrue(Arrays.asList(annotation.value()).contains(capability), methodName);
    }
}
