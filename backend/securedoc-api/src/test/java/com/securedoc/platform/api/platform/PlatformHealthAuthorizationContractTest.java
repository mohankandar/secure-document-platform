package com.securedoc.platform.api.platform;

import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class PlatformHealthAuthorizationContractTest {
    @Test
    void overviewIsReadableButActiveHealthCheckIsPlatformOnly() throws Exception {
        var overview = PlatformAdminController.class
                .getMethod("overview", HttpServletRequest.class)
                .getAnnotation(RequireCapability.class);
        var active = PlatformAdminController.class
                .getMethod("healthCheck", HttpServletRequest.class)
                .getAnnotation(RequireCapability.class);

        assertArrayEquals(new UserCapability[]{UserCapability.TECHNICAL_ADMIN_VIEW}, overview.value());
        assertArrayEquals(new UserCapability[]{UserCapability.PLATFORM_OPERATIONS}, active.value());
    }
}
