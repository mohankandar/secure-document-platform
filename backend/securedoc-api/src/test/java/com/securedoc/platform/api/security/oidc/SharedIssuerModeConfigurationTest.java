package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.security.SecurityProperties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedIssuerModeConfigurationTest {
    @Test void sharedIssuerTenantSelectionDefaultsOff() {
        SecurityProperties properties = new SecurityProperties();
        assertFalse(properties.getOidc().isSharedIssuerTenantSelectionEnabled());
        assertTrue(properties.getOidc().isPersistentConnectionEnabled());
    }

    @Test void sharedIssuerSelectionCanBeExplicitlyEnabled() {
        SecurityProperties properties = new SecurityProperties();
        properties.getOidc().setSharedIssuerTenantSelectionEnabled(true);
        assertTrue(properties.getOidc().isSharedIssuerTenantSelectionEnabled());
    }
}
