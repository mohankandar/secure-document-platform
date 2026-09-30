package com.documentplatform.identity.spring;

import com.documentplatform.identity.AuthenticatedIdentity;
import com.documentplatform.identity.IdentityClaimConfig;
import com.documentplatform.identity.IdentityClaims;
import org.springframework.security.oauth2.jwt.Jwt;

public final class OidcJwtIdentityNormalizer {
    private final String providerId;
    private final IdentityClaimConfig claimConfig;

    public OidcJwtIdentityNormalizer(String providerId, IdentityClaimConfig claimConfig) {
        this.providerId = providerId == null || providerId.isBlank() ? "oidc" : providerId.trim();
        this.claimConfig = claimConfig;
    }

    public AuthenticatedIdentity normalize(Jwt jwt) {
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        String subject = jwt.getSubject();
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("authenticated JWT must contain issuer and subject");
        }
        return IdentityClaims.normalize(providerId, issuer, subject, jwt.getClaims(), claimConfig);
    }
}
