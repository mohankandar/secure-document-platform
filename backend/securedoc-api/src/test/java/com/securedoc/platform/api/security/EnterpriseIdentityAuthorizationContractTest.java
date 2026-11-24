package com.securedoc.platform.api.security;

import com.securedoc.platform.api.identity.EnterpriseIdentityAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EnterpriseIdentityAuthorizationContractTest {
    @Test void theOverviewRequiresTenantAuthManagement(){
        Method method=Arrays.stream(EnterpriseIdentityAdminController.class.getDeclaredMethods()).filter(m->m.getName().equals("overview")).findFirst().orElseThrow();
        RequireCapability required=method.getAnnotation(RequireCapability.class); assertNotNull(required);
        assertTrue(Arrays.asList(required.value()).contains(UserCapability.TENANT_AUTH_MANAGE));
    }
    @Test void tenantItOwnsTenantAuthenticationAndPlatformOpsDoesNot(){
        ProductAuthorizationPolicy policy=new ProductAuthorizationPolicy();
        assertTrue(policy.capabilitiesFor(Set.of("TENANT_IT_ADMIN")).contains(UserCapability.TENANT_AUTH_MANAGE));
        assertFalse(policy.capabilitiesFor(Set.of("ADMIN")).contains(UserCapability.TENANT_AUTH_MANAGE));
        assertFalse(policy.capabilitiesFor(Set.of("PLATFORM_OPERATIONS_ADMIN")).contains(UserCapability.TENANT_AUTH_MANAGE));
        assertFalse(policy.capabilitiesFor(Set.of("BUSINESS_ADMIN")).contains(UserCapability.TENANT_AUTH_MANAGE));
    }
    /**
     * Task 0041: the screen is read-only. Sign-in connections are set through setup; no endpoint here writes. Task 0054: the
     * one POST is the test sign-in, which takes no input (no body, no parameter) and changes nothing (SignInConnectionTestTest).
     */
    @Test void theScreenHasNoWritingEndpoint(){
        for(Method m:EnterpriseIdentityAdminController.class.getDeclaredMethods()){
            if(m.getName().equals("testSignIn")){
                assertArrayEquals(new Class<?>[]{jakarta.servlet.http.HttpServletRequest.class},m.getParameterTypes());
                assertArrayEquals(new UserCapability[]{UserCapability.TENANT_AUTH_MANAGE},m.getAnnotation(RequireCapability.class).value());
                continue;
            }
            assertNull(m.getAnnotation(PostMapping.class),m.getName());
            assertNull(m.getAnnotation(PutMapping.class),m.getName());
            assertNull(m.getAnnotation(PatchMapping.class),m.getName());
            assertNull(m.getAnnotation(DeleteMapping.class),m.getName());
        }
        for(String gone:new String[]{"saveDraft","testDraft","activateDraft","rollback"}){
            assertTrue(Arrays.stream(EnterpriseIdentityAdminController.class.getDeclaredMethods()).noneMatch(m->m.getName().equals(gone)),gone);
        }
    }
}
