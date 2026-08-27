package com.securedoc.platform.domain.policy;

/**
 * Outcome of a policy rule evaluation against a finding.
 */
public enum PolicyDecision {

    /** Finding must be redacted. */
    REDACT,

    /** Finding requires human review before action. */
    REVIEW,

    /** Finding can be ignored/allowed. */
    ALLOW,

    /** Finding should be flagged but no automatic action taken. */
    FLAG,

    /** Policy could not determine a decision. */
    INDETERMINATE
}
