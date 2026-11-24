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
     * Task 0058: the screen changes the sign-in connection again (a draft, its test, switching, going back; 0041 had made it
     * read-only). Every endpoint needs the IT Admin's capability, none takes a connection id, a customer or an entity, and
     * the test of the active connection still takes no input at all (task 0054).
     */
    @Test void everyEndpointNeedsTheItAdminAndTakesNoConnectionCustomerOrEntity(){
        int endpoints=0;
        for(Method m:EnterpriseIdentityAdminController.class.getDeclaredMethods()){
            boolean endpoint=m.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class)!=null||m.getAnnotation(PostMapping.class)!=null
                    ||m.getAnnotation(PutMapping.class)!=null||m.getAnnotation(PatchMapping.class)!=null||m.getAnnotation(DeleteMapping.class)!=null;
            if(!endpoint) continue;
            endpoints++;
            assertArrayEquals(new UserCapability[]{UserCapability.TENANT_AUTH_MANAGE},m.getAnnotation(RequireCapability.class).value(),m.getName());
            for(java.lang.reflect.Parameter p:m.getParameters()){
                assertNull(p.getAnnotation(org.springframework.web.bind.annotation.PathVariable.class),m.getName());
                assertNull(p.getAnnotation(org.springframework.web.bind.annotation.RequestParam.class),m.getName());
                if(p.getType().isRecord()) for(var part:p.getType().getRecordComponents()){
                    assertTrue(Set.of("expectedVersion","providerId","issuerUri","browserClientId","apiAudience","token").contains(part.getName()),part.getName());
                }
            }
            if(m.getName().equals("testSignIn")) assertArrayEquals(new Class<?>[]{jakarta.servlet.http.HttpServletRequest.class},m.getParameterTypes());
        }
        assertEquals(8,endpoints);
    }
}
