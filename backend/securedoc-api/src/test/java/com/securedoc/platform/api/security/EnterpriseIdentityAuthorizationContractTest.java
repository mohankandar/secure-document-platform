package com.securedoc.platform.api.security;

import com.securedoc.platform.api.identity.EnterpriseIdentityAdminController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EnterpriseIdentityAuthorizationContractTest {
    @Test void everyCheckpointBEndpointRequiresTenantAuthManagement(){
        for(String name:new String[]{"overview","saveDraft","testDraft","activateDraft","rollback"}){
            Method method=Arrays.stream(EnterpriseIdentityAdminController.class.getDeclaredMethods()).filter(m->m.getName().equals(name)).findFirst().orElseThrow();
            RequireCapability required=method.getAnnotation(RequireCapability.class); assertNotNull(required,name);
            assertTrue(Arrays.asList(required.value()).contains(UserCapability.TENANT_AUTH_MANAGE),name);
        }
    }
    @Test void tenantItOwnsTenantAuthenticationAndPlatformOpsDoesNot(){
        ProductAuthorizationPolicy policy=new ProductAuthorizationPolicy();
        assertTrue(policy.capabilitiesFor(Set.of("TENANT_IT_ADMIN")).contains(UserCapability.TENANT_AUTH_MANAGE));
        assertTrue(policy.capabilitiesFor(Set.of("ADMIN")).contains(UserCapability.TENANT_AUTH_MANAGE));
        assertFalse(policy.capabilitiesFor(Set.of("PLATFORM_OPERATIONS_ADMIN")).contains(UserCapability.TENANT_AUTH_MANAGE));
        assertFalse(policy.capabilitiesFor(Set.of("BUSINESS_ADMIN")).contains(UserCapability.TENANT_AUTH_MANAGE));
    }
    @Test void checkpointCExposesExplicitActivationAndRollbackMethods(){
        assertTrue(Arrays.stream(EnterpriseIdentityAdminController.class.getDeclaredMethods()).anyMatch(m->m.getName().equals("activateDraft")));
        assertTrue(Arrays.stream(EnterpriseIdentityAdminController.class.getDeclaredMethods()).anyMatch(m->m.getName().equals("rollback")));
    }
}
