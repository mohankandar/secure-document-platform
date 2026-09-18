package com.securedoc.platform.application.licensing;

import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.licensing.LicenseClaims;
import com.securedoc.platform.domain.licensing.LicenseVerificationResult;
import com.securedoc.platform.domain.licensing.TenantLicenseRecord;

import java.time.Instant;
import java.util.Optional;

/** Outbound port for verifying vendor-signed license tokens. */
public interface LicenseTokenVerifier {
    LicenseVerificationResult verify(String signedToken, TenantId expectedTenantId, Instant now);
    boolean isVerificationKeyConfigured();

    /**
     * Recovers claims from a license record that was cryptographically verified before it was persisted.
     * Implementations must validate the persisted payload fingerprint and immutable tenant/license identifiers.
     * This is for audit/history rendering only; runtime authorization must always use {@link #verify}.
     */
    default Optional<LicenseClaims> readPersistedVerifiedClaims(TenantLicenseRecord record) {
        return Optional.empty();
    }
}
