package com.securedoc.platform.api.security;

import com.securedoc.platform.api.controller.BusinessAdminController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class DocumentIntelligenceAuthorizationContractTest {
    @Test
    void documentIntelligenceMutationsRemainBusinessPolicyAdministration() {
        for (String methodName : new String[]{"updateDocumentIntelligence", "rollbackDocumentIntelligence", "markDocumentIntelligenceLastKnownGood"}) {
            Method method = Arrays.stream(BusinessAdminController.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName)).findFirst().orElseThrow();
            RequireCapability annotation = method.getAnnotation(RequireCapability.class);
            assertNotNull(annotation, methodName);
            assertTrue(Arrays.asList(annotation.value()).contains(UserCapability.TENANT_POLICY_MANAGE), methodName);
        }
    }

    @Test
    void technicalRolesDoNotGainBusinessPolicyManagementThroughThisUnit() {
        ProductAuthorizationPolicy policy = new ProductAuthorizationPolicy();
        assertTrue(policy.capabilitiesFor(java.util.Set.of("BUSINESS_ADMIN")).contains(UserCapability.TENANT_POLICY_MANAGE));
        assertFalse(policy.capabilitiesFor(java.util.Set.of("TENANT_IT_ADMIN")).contains(UserCapability.TENANT_POLICY_MANAGE));
        assertFalse(policy.capabilitiesFor(java.util.Set.of("PLATFORM_OPERATIONS_ADMIN")).contains(UserCapability.TENANT_POLICY_MANAGE));
    }
}
