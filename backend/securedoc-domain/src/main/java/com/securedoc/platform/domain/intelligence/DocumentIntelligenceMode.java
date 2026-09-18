package com.securedoc.platform.domain.intelligence;

/**
 * Tenant-controlled activation mode for optional adaptive/document-profile intelligence.
 *
 * <p>DISABLED preserves the frozen baseline processing behavior. OBSERVE allows installed
 * intelligence modules to evaluate a document without influencing any business decision.
 * ENABLED permits installed intelligence modules to contribute priors/context, while the
 * baseline discovery path remains mandatory.</p>
 */
public enum DocumentIntelligenceMode {
    DISABLED,
    OBSERVE,
    ENABLED;

    public boolean executionAllowed() {
        return this != DISABLED;
    }

    public boolean decisionInfluenceAllowed() {
        return this == ENABLED;
    }
}
