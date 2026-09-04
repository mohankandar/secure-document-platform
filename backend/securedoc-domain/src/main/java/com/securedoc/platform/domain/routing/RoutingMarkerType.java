package com.securedoc.platform.domain.routing;

/** Deterministic V1 marker families. AI is never used to select a routing destination. */
public enum RoutingMarkerType {
    BARCODE,
    QR_CODE,
    TEXT_PATTERN
}
