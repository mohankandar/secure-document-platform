package com.securedoc.platform.api.security;

/** Product-defined V1 user capabilities. Tenant administrators assign roles; tenants do not redefine this mapping. */
public enum UserCapability {
    MANUAL_REVIEW,
    PACKAGE_APPROVE_DELIVERY,
    PACKAGE_REPROCESS,
    PACKAGE_REMOVE_FROM_DASHBOARD,
    TENANT_POLICY_MANAGE,
    TENANT_INTEGRATION_MANAGE,
    TECHNICAL_EXCEPTION_VIEW,
    TECHNICAL_ADMIN_VIEW,
    TECHNICAL_RETRY,
    TECHNICAL_HOLD_MANAGE,
    AUDIT_VIEW,
    REPORT_EXECUTIVE_VIEW,
    REPORT_OPERATIONAL_VIEW,
    REPORT_EXPORT,
    PLATFORM_OPERATIONS
}
