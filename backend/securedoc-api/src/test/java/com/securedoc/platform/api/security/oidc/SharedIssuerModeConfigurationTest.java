package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.security.SecurityMode;
import com.securedoc.platform.api.security.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0058: sign-in through platform-identity with one or several entities is the only way; there is no setting for it. */
class SharedIssuerModeConfigurationTest {

    @Test
    void thereIsNoSettingThatTurnsItOffAndTheStoredConnectionIsUsedByDefault() {
        assertTrue(Arrays.stream(SecurityProperties.Oidc.class.getDeclaredMethods()).noneMatch(m -> m.getName().contains("SharedIssuer")
                || m.getName().contains("TenantClaim") || m.getName().contains("TenantId") || m.getName().contains("RoleMappings")));
        assertTrue(new SecurityProperties().getOidc().isPersistentConnectionEnabled());
        assertEquals(SecurityMode.OIDC, new SecurityProperties().getMode());
    }

    /** An installation that still has the removed settings starts as without them (SETUP.md says so). */
    @Test
    void aConfigurationWithTheRemovedSettingsBindsAsWithoutThem() {
        var old = new MapConfigurationPropertySource(Map.of(
                "securedoc.security.oidc.shared-issuer-tenant-selection-enabled", "false",
                "securedoc.security.oidc.tenant-id", "00000000-0000-0000-0000-000000000001",
                "securedoc.security.oidc.tenant-claim", "tenant_id",
                "securedoc.security.oidc.role-mappings.BUSINESS_ADMIN", "some-group",
                "securedoc.security.oidc.audience", "lexavault-api"));
        SecurityProperties bound = new Binder(old).bind("securedoc.security", SecurityProperties.class).get();
        assertEquals("lexavault-api", bound.getOidc().getAudience());
        assertTrue(bound.getOidc().isPersistentConnectionEnabled());
    }
}
