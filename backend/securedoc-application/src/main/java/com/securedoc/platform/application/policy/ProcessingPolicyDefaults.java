package com.securedoc.platform.application.policy;

/**
 * Backward-compatible V1 default value. Runtime processing now reads tenant-owned
 * {@code TenantRedactionPolicy}; this constant is not the authoritative policy source.
 */
public final class ProcessingPolicyDefaults {
    public static final double AUTO_REDACT_THRESHOLD = 0.80;

    private ProcessingPolicyDefaults() {}
}
