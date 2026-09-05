package com.securedoc.platform.api.security;

import com.securedoc.platform.api.controller.ManualFallbackController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualFallbackAuthorizationContractTest {

    @Test
    void documentProcessorCanPerformBusinessManualFallbackWithoutTechnicalAdminCapability() {
        Set<UserCapability> capabilities = new ProductAuthorizationPolicy().capabilitiesFor(Set.of("DOCUMENT_PROCESSOR"));
        assertTrue(capabilities.contains(UserCapability.MANUAL_REVIEW));
        assertTrue(capabilities.contains(UserCapability.REDACTION_ADJUST));
        assertTrue(capabilities.contains(UserCapability.REVIEW_SUBMIT));
        assertTrue(!capabilities.contains(UserCapability.TECHNICAL_RETRY));
    }

    @Test
    void manualFallbackEndpointsReuseExistingBusinessCapabilities() {
        assertCapability("start", UserCapability.MANUAL_REVIEW);
        assertCapability("detail", UserCapability.MANUAL_REVIEW);
        assertCapability("preview", UserCapability.MANUAL_REVIEW);
        assertCapability("addRegion", UserCapability.REDACTION_ADJUST);
        assertCapability("removeRegion", UserCapability.REDACTION_ADJUST);
        assertCapability("submit", UserCapability.REVIEW_SUBMIT);
    }

    private void assertCapability(String methodName, UserCapability capability) {
        Method method = Arrays.stream(ManualFallbackController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        assertArrayEquals(new UserCapability[]{capability}, method.getAnnotation(RequireCapability.class).value());
    }
}
