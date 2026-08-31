package com.securedoc.platform.application.operations.model;

/** Business-facing operational stage used by the shared dashboard. */
public enum DashboardStage {
    ALL,
    PROCESSING,
    NEEDS_REVIEW,
    NEEDS_APPROVAL,
    EXPORTED,
    TECHNICAL_ATTENTION,
    REJECTED
}
