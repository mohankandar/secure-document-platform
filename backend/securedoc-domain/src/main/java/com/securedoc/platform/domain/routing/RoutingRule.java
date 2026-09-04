package com.securedoc.platform.domain.routing;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Business-owned mapping from a deterministic routing marker to an IT-managed destination. */
public final class RoutingRule {
    private final UUID id;
    private final TenantId tenantId;
    private String destinationLabel;
    private RoutingMarkerType markerType;
    private String markerValue;
    private RoutingScanRegion scanRegion;
    private IntegrationConnectionId destinationConnectionId;
    private boolean enabled;
    private final Instant createdAt;
    private Instant updatedAt;

    private RoutingRule(UUID id, TenantId tenantId, String destinationLabel, RoutingMarkerType markerType,
                        String markerValue, RoutingScanRegion scanRegion, IntegrationConnectionId destinationConnectionId,
                        boolean enabled, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.createdAt = Objects.requireNonNull(createdAt);
        apply(destinationLabel, markerType, markerValue, scanRegion, destinationConnectionId, enabled, updatedAt);
    }

    public static RoutingRule create(TenantId tenantId, String destinationLabel, RoutingMarkerType markerType,
                                     String markerValue, RoutingScanRegion scanRegion,
                                     IntegrationConnectionId destinationConnectionId, boolean enabled, Instant now) {
        return new RoutingRule(UUID.randomUUID(), tenantId, destinationLabel, markerType, markerValue,
                scanRegion, destinationConnectionId, enabled, now, now);
    }

    public static RoutingRule reconstitute(UUID id, TenantId tenantId, String destinationLabel,
                                            RoutingMarkerType markerType, String markerValue,
                                            RoutingScanRegion scanRegion, IntegrationConnectionId destinationConnectionId,
                                            boolean enabled, Instant createdAt, Instant updatedAt) {
        return new RoutingRule(id, tenantId, destinationLabel, markerType, markerValue, scanRegion,
                destinationConnectionId, enabled, createdAt, updatedAt);
    }

    public void update(String destinationLabel, RoutingMarkerType markerType, String markerValue,
                       RoutingScanRegion scanRegion, IntegrationConnectionId destinationConnectionId,
                       boolean enabled, Instant now) {
        apply(destinationLabel, markerType, markerValue, scanRegion, destinationConnectionId, enabled, now);
    }

    private void apply(String destinationLabel, RoutingMarkerType markerType, String markerValue,
                       RoutingScanRegion scanRegion, IntegrationConnectionId destinationConnectionId,
                       boolean enabled, Instant now) {
        if (destinationLabel == null || destinationLabel.isBlank()) throw new IllegalArgumentException("Destination label is required");
        if (destinationLabel.trim().length() > 120) throw new IllegalArgumentException("Destination label must be <= 120 characters");
        if (markerValue == null || markerValue.isBlank()) throw new IllegalArgumentException("Routing marker value is required");
        if (markerValue.trim().length() > 200) throw new IllegalArgumentException("Routing marker value must be <= 200 characters");
        this.destinationLabel = destinationLabel.trim();
        this.markerType = Objects.requireNonNull(markerType, "Routing marker type is required");
        this.markerValue = markerValue.trim();
        this.scanRegion = Objects.requireNonNull(scanRegion, "Routing scan region is required");
        this.destinationConnectionId = Objects.requireNonNull(destinationConnectionId, "Destination connection is required");
        this.enabled = enabled;
        this.updatedAt = Objects.requireNonNull(now);
    }

    public UUID id() { return id; }
    public TenantId tenantId() { return tenantId; }
    public String destinationLabel() { return destinationLabel; }
    public RoutingMarkerType markerType() { return markerType; }
    public String markerValue() { return markerValue; }
    public RoutingScanRegion scanRegion() { return scanRegion; }
    public IntegrationConnectionId destinationConnectionId() { return destinationConnectionId; }
    public boolean enabled() { return enabled; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
