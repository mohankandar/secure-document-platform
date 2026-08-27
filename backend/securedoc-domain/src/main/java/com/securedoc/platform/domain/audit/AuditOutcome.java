package com.securedoc.platform.domain.audit;

/**
 * Outcome of an audited action.
 */
public enum AuditOutcome {

    /** Action completed successfully. */
    SUCCESS,

    /** Action failed. */
    FAILURE,

    /** Action was denied by access control or policy. */
    DENIED,

    /** Action was partially completed. */
    PARTIAL
}
