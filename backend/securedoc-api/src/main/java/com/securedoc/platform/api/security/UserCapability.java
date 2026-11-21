package com.securedoc.platform.api.security;

/** Product-defined V1 user capabilities. Tenant administrators assign roles; tenants do not redefine this mapping. */
public enum UserCapability {
    DASHBOARD_ACCESS,
    DOCUMENT_INTAKE,
    MANUAL_REVIEW,
    FINDING_DECISION,
    REDACTION_ADJUST,
    REVIEW_SUBMIT,
    PACKAGE_APPROVE_DELIVERY,
    /** Task 0028: resend exports waiting for a person. Processor and Supervisor; approving delivery stays Supervisor only. */
    EXPORT_RESEND,
    PACKAGE_REPROCESS,
    PACKAGE_REMOVE_FROM_DASHBOARD,
    REPORT_EXECUTIVE_VIEW,
    REPORT_OPERATIONAL_VIEW,
    REPORT_EXPORT,
    /** Compliance report PDF, summary edition (task 0017). Business Administrator only. */
    COMPLIANCE_REPORT_SUMMARY,
    /** Compliance report PDF with the full audit trail appendix (task 0017). Tenant IT Administrator only. */
    COMPLIANCE_REPORT_FULL,
    TENANT_POLICY_MANAGE,
    TENANT_IDENTIFIER_MANAGE,
    TENANT_INTEGRATION_MANAGE,
    TENANT_AUTH_MANAGE,
    TECHNICAL_EXCEPTION_VIEW,
    TECHNICAL_ADMIN_VIEW,
    TECHNICAL_RETRY,
    TECHNICAL_HOLD_MANAGE,
    AUDIT_VIEW,
    PLATFORM_DIAGNOSTICS,
    PLATFORM_OPERATIONS
}
