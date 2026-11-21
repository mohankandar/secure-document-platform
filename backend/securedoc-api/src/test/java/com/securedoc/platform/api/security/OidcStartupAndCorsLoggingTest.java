package com.securedoc.platform.api.security;

import com.securedoc.platform.api.security.oidc.OidcConnectionBootstrap;
import com.securedoc.platform.api.testsupport.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0023 C7 (#327, #348). */
class OidcStartupAndCorsLoggingTest {

    @Test
    void aRefusedBrowserOriginIsLogged() {
        SecurityProperties properties = new SecurityProperties();
        properties.getCors().setAllowedOrigins("https://app.example.test");
        try (LogCapture logs = LogCapture.of("com.securedoc.platform.api.security.Cors")) {
            CorsConfiguration config = new SecurityConfig().corsConfigurationSource(properties)
                    .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/me"));
            assertEquals("https://app.example.test", config.checkOrigin("https://app.example.test"));
            assertNull(config.checkOrigin("https://evil.example.test"));
            assertNull(config.checkOrigin("https://x.test/<script>"));
            assertTrue(logs.text().contains("CORS_ORIGIN_REJECTED origin=https://evil.example.test allowedOrigins=1"));
            assertTrue(logs.text().contains("CORS_ORIGIN_REJECTED origin=unrecognised"));
        }
    }

    @Test
    void emptyOriginsInOidcModeAreLoggedAtStartUp() {
        SecurityProperties properties = new SecurityProperties();
        properties.setMode(SecurityMode.OIDC);
        try (LogCapture logs = LogCapture.of("com.securedoc.platform.api.security.Cors")) {
            new SecurityConfig().corsConfigurationSource(properties);
            assertTrue(logs.text().contains("CORS_ALLOWED_ORIGINS_EMPTY"));
        }
    }

    @Test
    void aSkippedBootstrapIsLoggedWithTheMissingSettingNames() {
        SecurityProperties properties = new SecurityProperties();
        properties.setMode(SecurityMode.OIDC);
        properties.getOidc().setPersistentConnectionEnabled(true);
        properties.getOidc().setIssuerUri("https://login.example.test");
        try (LogCapture logs = LogCapture.of(OidcConnectionBootstrap.class)) {
            new OidcConnectionBootstrap(properties, null).run(null);
            assertTrue(logs.text().contains("OIDC_CONNECTION_BOOTSTRAP_SKIPPED reason=SETTINGS_MISSING"), logs.text());
            assertTrue(logs.text().contains("tenant-id"));
            assertFalse(logs.text().contains("login.example.test"));
        }
    }
}
