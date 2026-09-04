package com.securedoc.platform.domain.routing;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RoutingRuleTest {
    @Test
    void ruleRequiresNamedApprovedDestinationAndFixedRegion() {
        TenantId tenant = TenantId.generate();
        IntegrationConnectionId destination = IntegrationConnectionId.generate();
        Instant now = Instant.parse("2026-09-03T17:00:00Z");

        RoutingRule rule = RoutingRule.create(tenant, "Atlanta", RoutingMarkerType.TEXT_PATTERN,
                "|||||888888|||||||", RoutingScanRegion.BOTH, destination, true, now);
        assertEquals("Atlanta", rule.destinationLabel());
        assertEquals(RoutingScanRegion.BOTH, rule.scanRegion());
        assertEquals(destination, rule.destinationConnectionId());
        assertTrue(rule.enabled());

        assertThrows(IllegalArgumentException.class, () -> RoutingRule.create(
                tenant, " ", RoutingMarkerType.TEXT_PATTERN, "77777777", RoutingScanRegion.TOP_RIGHT,
                destination, true, now));
        assertThrows(NullPointerException.class, () -> RoutingRule.create(
                tenant, "SC", RoutingMarkerType.TEXT_PATTERN, "77777777", null,
                destination, true, now));
    }
}
