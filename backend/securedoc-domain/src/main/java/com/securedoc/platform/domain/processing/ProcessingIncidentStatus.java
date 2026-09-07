package com.securedoc.platform.domain.processing;

/** Durable lifecycle of a technical processing incident, independent from business completion. */
public enum ProcessingIncidentStatus {
    OPEN,
    RETRY_SCHEDULED,
    BUSINESS_CONTINUED,
    RESOLVED,
    ESCALATED
}
