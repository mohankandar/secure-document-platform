package com.securedoc.platform.domain.routing;

import com.securedoc.platform.domain.identity.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DerivedDeliveryTest {
    private final TenantId tenantId = TenantId.generate();
    private final PackageId packageId = PackageId.generate();
    private final DocumentId documentId = DocumentId.generate();
    private final IntegrationConnectionId connectionId = IntegrationConnectionId.generate();
    private final Instant now = Instant.parse("2026-09-03T17:00:00Z");

    @Test
    void deliveryLifecycleIsIndependentAndPreservesExportCycle() {
        RoutingRule rule = RoutingRule.create(tenantId, "South Carolina", RoutingMarkerType.TEXT_PATTERN,
                "77777777", RoutingScanRegion.TOP_RIGHT, connectionId, true, now);
        DerivedDelivery delivery = DerivedDelivery.create(tenantId, packageId, documentId, 2, rule, 4,
                List.of(2, 5, 9), ArtifactId.generate(), "loan__South_Carolina.pdf", now);

        assertEquals(2, delivery.exportCycle());
        assertEquals(DerivedDeliveryStatus.PENDING, delivery.status());
        assertEquals(List.of(2, 5, 9), delivery.pageNumbers());

        delivery.startAttempt(now.plusSeconds(1));
        assertEquals(1, delivery.attemptCount());
        assertEquals(DerivedDeliveryStatus.DELIVERING, delivery.status());

        delivery.markFailure("DELIVERY_IO", true, now.plusSeconds(2));
        assertEquals(DerivedDeliveryStatus.RETRY_PENDING, delivery.status());
        assertTrue(delivery.retryable());

        delivery.startAttempt(now.plusSeconds(3));
        assertEquals(2, delivery.attemptCount());
        delivery.markDelivered("receipt-123", now.plusSeconds(4));
        assertEquals(DerivedDeliveryStatus.DELIVERED, delivery.status());
        assertEquals("receipt-123", delivery.destinationReference());
        assertFalse(delivery.retryable());
    }

    @Test
    void routingAttentionCannotBeDeliveredAndIsCycleScoped() {
        DerivedDelivery attention = DerivedDelivery.routingAttention(tenantId, packageId, documentId, 3,
                List.of(7), "ROUTING_MARKER_AMBIGUOUS", now);
        assertEquals(3, attention.exportCycle());
        assertEquals(DerivedDeliveryStatus.ATTENTION_REQUIRED, attention.status());
        assertNull(attention.artifactId());
        assertThrows(IllegalStateException.class, () -> attention.startAttempt(now.plusSeconds(1)));
    }

    @Test
    void exportCycleMustBePositive() {
        RoutingRule rule = RoutingRule.create(tenantId, "Atlanta", RoutingMarkerType.BARCODE,
                "888888", RoutingScanRegion.BOTTOM_LEFT, connectionId, true, now);
        assertThrows(IllegalArgumentException.class, () -> DerivedDelivery.create(
                tenantId, packageId, documentId, 0, rule, 1, List.of(1), ArtifactId.generate(), "a.pdf", now));
    }
}
