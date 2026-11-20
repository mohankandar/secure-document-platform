package com.securedoc.platform.application.compliance;

/** The two editions of the compliance report (task 0017). */
public enum ComplianceEdition {
    /** Audit activity as counts. Business Administrator. */
    SUMMARY,
    /** The summary plus every audit record of the period as an appendix. Tenant IT Administrator. */
    FULL
}
