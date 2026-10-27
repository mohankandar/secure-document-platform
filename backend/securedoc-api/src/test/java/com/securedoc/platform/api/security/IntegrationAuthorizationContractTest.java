package com.securedoc.platform.api.security;

import com.securedoc.platform.api.integration.IntegrationConnectionController;
import com.securedoc.platform.api.integration.PollingIntegrationController;
import com.securedoc.platform.api.integration.SdvIntegrationController;
import com.securedoc.platform.api.routing.DerivedIntegrationController;
import com.securedoc.platform.api.routing.RoutingRuleController;
import com.securedoc.platform.api.routing.DerivedDeliveryController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationAuthorizationContractTest {
    @Test
    void everyIntegrationManagementEndpointRequiresTenantIntegrationCapability() {
        for (String methodName : new String[]{"list", "get", "create", "update", "enable", "disable"}) {
            Method method = Arrays.stream(IntegrationConnectionController.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst().orElseThrow();
            RequireCapability annotation = method.getAnnotation(RequireCapability.class);
            assertNotNull(annotation, methodName);
            assertTrue(Arrays.asList(annotation.value()).contains(UserCapability.TENANT_INTEGRATION_MANAGE), methodName);
        }
    }

    @Test
    void onlyTenantItAdminReceivesIntegrationManagementCapability() {
        ProductAuthorizationPolicy policy = new ProductAuthorizationPolicy();
        assertTrue(policy.capabilitiesFor(java.util.Set.of("TENANT_IT_ADMIN")).contains(UserCapability.TENANT_INTEGRATION_MANAGE));
        assertFalse(policy.capabilitiesFor(java.util.Set.of("PLATFORM_OPERATIONS_ADMIN")).contains(UserCapability.TENANT_INTEGRATION_MANAGE));
        assertFalse(policy.capabilitiesFor(java.util.Set.of("ADMIN")).contains(UserCapability.TENANT_INTEGRATION_MANAGE));
        assertFalse(policy.capabilitiesFor(java.util.Set.of("BUSINESS_ADMIN")).contains(UserCapability.TENANT_INTEGRATION_MANAGE));
        assertFalse(policy.capabilitiesFor(java.util.Set.of("DOCUMENT_PROCESSOR")).contains(UserCapability.TENANT_INTEGRATION_MANAGE));
    }
    @Test
    void derivedRoutingIsTenantItOwnedNotBusinessOrPlatformOwned() {
        ProductAuthorizationPolicy policy = new ProductAuthorizationPolicy();
        assertTrue(policy.capabilitiesFor(java.util.Set.of("TENANT_IT_ADMIN"))
                .contains(UserCapability.TENANT_INTEGRATION_MANAGE));
        assertFalse(policy.capabilitiesFor(java.util.Set.of("BUSINESS_ADMIN"))
                .contains(UserCapability.TENANT_INTEGRATION_MANAGE));
        assertFalse(policy.capabilitiesFor(java.util.Set.of("PLATFORM_OPERATIONS_ADMIN"))
                .contains(UserCapability.TENANT_INTEGRATION_MANAGE));
    }

    @Test
    void pollingRunNowAndStatusRequireTenantIntegrationCapability() {
        for (String methodName : new String[]{"status", "runNow", "testConnection"}) {
            Method method = Arrays.stream(PollingIntegrationController.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst().orElseThrow();
            RequireCapability annotation = method.getAnnotation(RequireCapability.class);
            assertNotNull(annotation, methodName);
            assertTrue(Arrays.asList(annotation.value()).contains(UserCapability.TENANT_INTEGRATION_MANAGE), methodName);
        }
    }

    @Test
    void sdvTestConnectionRequiresTenantIntegrationCapability() {
        Method method = Arrays.stream(SdvIntegrationController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("testConnection"))
                .findFirst().orElseThrow();
        RequireCapability annotation = method.getAnnotation(RequireCapability.class);
        assertNotNull(annotation);
        assertTrue(Arrays.asList(annotation.value()).contains(UserCapability.TENANT_INTEGRATION_MANAGE));
    }

    @Test
    void derivedRoutingEndpointsKeepBusinessAndTechnicalAdministrationSeparated() {
        Method derivedTest = Arrays.stream(DerivedIntegrationController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("test")).findFirst().orElseThrow();
        assertTrue(Arrays.asList(derivedTest.getAnnotation(RequireCapability.class).value()).contains(UserCapability.TENANT_INTEGRATION_MANAGE));

        for (String methodName : new String[]{"list", "destinations", "create", "update", "delete"}) {
            Method method = Arrays.stream(RoutingRuleController.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName)).findFirst().orElseThrow();
            assertTrue(Arrays.asList(method.getAnnotation(RequireCapability.class).value()).contains(UserCapability.TENANT_INTEGRATION_MANAGE), methodName);
        }

        Method send = Arrays.stream(DerivedDeliveryController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("send")).findFirst().orElseThrow();
        assertTrue(Arrays.asList(send.getAnnotation(RequireCapability.class).value()).contains(UserCapability.PACKAGE_APPROVE_DELIVERY));
        Method retry = Arrays.stream(DerivedDeliveryController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("retry")).findFirst().orElseThrow();
        assertTrue(Arrays.asList(retry.getAnnotation(RequireCapability.class).value()).contains(UserCapability.TECHNICAL_RETRY));
    }

}
