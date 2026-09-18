package com.securedoc.platform.domain.licensing;

/** Result returned by the cryptographic verifier. */
public record LicenseVerificationResult(
        LicenseValidationStatus status,
        LicenseClaims claims,
        String reasonCode
) {
    public static LicenseVerificationResult of(LicenseValidationStatus status, LicenseClaims claims, String reasonCode) {
        return new LicenseVerificationResult(status, claims, reasonCode);
    }

    public boolean signatureAndTenantValid() {
        return status == LicenseValidationStatus.ACTIVE
                || status == LicenseValidationStatus.NOT_YET_VALID
                || status == LicenseValidationStatus.EXPIRED;
    }
}
