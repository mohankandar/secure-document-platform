package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Map;

/** Supplies the current privacy-safe processing/intelligence version fingerprint. */
@FunctionalInterface
public interface ProcessingFingerprintProvider {
    Map<String, String> currentFingerprint(TenantId tenantId, PackageId packageId);

    static ProcessingFingerprintProvider empty() {
        return (tenantId, packageId) -> Map.of();
    }
}
