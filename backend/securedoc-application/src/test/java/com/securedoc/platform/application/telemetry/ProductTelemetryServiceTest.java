package com.securedoc.platform.application.telemetry;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProductTelemetryServiceTest {

    @Test
    void telemetryIsOffByDefaultAndWhenInstallationIdIsInvalid() {
        ProductTelemetryService defaultOff = new ProductTelemetryService(null, null, "1.0.0");
        assertEquals(ProductTelemetryMode.OFF, defaultOff.mode());
        assertFalse(defaultOff.enabled());
        assertFalse(defaultOff.installationIdConfigured());

        ProductTelemetryService invalid = new ProductTelemetryService("BASIC", "customer-name-not-a-uuid", "1.0.0");
        assertEquals(ProductTelemetryMode.OFF, invalid.mode());
        assertFalse(invalid.enabled());
        assertFalse(invalid.installationIdConfigured());
    }

    @Test
    void basicAndEnhancedRequireOnlyPseudonymousInstallationIdentifier() {
        String installationId = UUID.randomUUID().toString();
        ProductTelemetryService basic = new ProductTelemetryService("BASIC", installationId, "1.0.0");
        ProductTelemetryService enhanced = new ProductTelemetryService("ENHANCED", installationId, "1.0.0");

        assertEquals(ProductTelemetryMode.BASIC, basic.mode());
        assertTrue(basic.enabled());
        assertTrue(basic.installationIdConfigured());
        assertEquals(ProductTelemetryMode.ENHANCED, enhanced.mode());
        assertTrue(enhanced.enabled());
        assertTrue(enhanced.installationIdConfigured());
    }

    @Test
    void unknownModeFailsClosed() {
        ProductTelemetryService service = new ProductTelemetryService(
                "SOMETHING_ELSE", UUID.randomUUID().toString(), "1.0.0");
        assertEquals(ProductTelemetryMode.OFF, service.mode());
        assertFalse(service.enabled());
    }
}
