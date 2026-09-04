package com.securedoc.platform.application.routing.model;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.routing.RoutingMarkerType;
import com.securedoc.platform.domain.routing.RoutingScanRegion;

public record RoutingRuleInput(String destinationLabel, RoutingMarkerType markerType, String markerValue,
                               RoutingScanRegion scanRegion, IntegrationConnectionId destinationConnectionId,
                               boolean enabled) { }
