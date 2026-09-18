package com.securedoc.platform.domain.intelligence;

import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;

/** Tenant-owned reversible document-intelligence activation and pinning policy. */
public record TenantDocumentIntelligencePolicy(
        TenantId tenantId,
        DocumentIntelligenceMode mode,
        String activeBundleVersion,
        String lastKnownGoodBundleVersion,
        int configurationVersion,
        Instant updatedAt) {

    public TenantDocumentIntelligencePolicy {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(mode, "Document intelligence mode is required");
        activeBundleVersion = requireVersion(activeBundleVersion, "Active intelligence bundle version");
        lastKnownGoodBundleVersion = requireVersion(lastKnownGoodBundleVersion, "Last-known-good intelligence bundle version");
        if (configurationVersion < 1) throw new IllegalArgumentException("Intelligence configuration version must be >= 1");
        Objects.requireNonNull(updatedAt, "Intelligence policy updated time is required");
    }

    public static TenantDocumentIntelligencePolicy defaults(TenantId tenantId, Instant now) {
        return new TenantDocumentIntelligencePolicy(tenantId, DocumentIntelligenceMode.DISABLED,
                DocumentIntelligenceBundle.R11_BASELINE_VERSION,
                DocumentIntelligenceBundle.R11_BASELINE_VERSION, 1, now);
    }

    public TenantDocumentIntelligencePolicy reconfigure(DocumentIntelligenceMode newMode,
                                                         String newActiveBundleVersion,
                                                         Instant now) {
        return new TenantDocumentIntelligencePolicy(tenantId, Objects.requireNonNull(newMode),
                newActiveBundleVersion, lastKnownGoodBundleVersion, configurationVersion + 1, now);
    }

    public TenantDocumentIntelligencePolicy markCurrentAsLastKnownGood(Instant now) {
        return new TenantDocumentIntelligencePolicy(tenantId, mode, activeBundleVersion,
                activeBundleVersion, configurationVersion + 1, now);
    }

    public TenantDocumentIntelligencePolicy rollback(Instant now) {
        return new TenantDocumentIntelligencePolicy(tenantId, mode, lastKnownGoodBundleVersion,
                lastKnownGoodBundleVersion, configurationVersion + 1, now);
    }

    private static String requireVersion(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(label + " is required");
        String normalized = value.trim();
        if (normalized.length() > 80) throw new IllegalArgumentException(label + " is too long");
        return normalized;
    }
}
