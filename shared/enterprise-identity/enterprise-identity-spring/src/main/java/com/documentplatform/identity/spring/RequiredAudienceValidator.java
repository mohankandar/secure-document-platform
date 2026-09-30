package com.documentplatform.identity.spring;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public final class RequiredAudienceValidator implements OAuth2TokenValidator<Jwt> {
    private final String audience;

    public RequiredAudienceValidator(String audience) {
        if (audience == null || audience.isBlank()) throw new IllegalArgumentException("audience is required");
        this.audience = audience.trim();
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        return token.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Required API audience is missing.", null));
    }
}
