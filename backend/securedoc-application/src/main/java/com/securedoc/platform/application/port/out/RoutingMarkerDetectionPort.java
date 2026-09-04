package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.routing.RoutingRule;

import java.io.InputStream;
import java.util.List;

/** Deterministic marker scan against fixed V1 page regions only. */
public interface RoutingMarkerDetectionPort {
    RoutingMarkerDetectionResult detect(InputStream redactedPdf, List<RoutingRule> rules);
}
