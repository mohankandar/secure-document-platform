package com.securedoc.platform.domain.processing;

/** Safe, structured incident-history events. */
public enum ProcessingIncidentEventType {
    FAILURE_RECORDED,
    RETRY_SCHEDULED,
    BUSINESS_CONTINUITY_STARTED,
    BUSINESS_CONTINUITY_SUBMITTED,
    BUSINESS_COMPLETED,
    RESOLVED,
    ESCALATED
}
