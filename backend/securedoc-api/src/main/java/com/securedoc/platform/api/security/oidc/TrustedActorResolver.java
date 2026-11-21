package com.securedoc.platform.api.security.oidc;

import com.documentplatform.identity.StableIdentityId;
import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.domain.identity.ActorId;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The actor id for a verified token: Enterprise Identity's {@link StableIdentityId} of issuer and subject.
 * Identical to the ids LexaVault produced before 0.3.0 for every unpadded value (ActorIdLibraryParityTest); values
 * padded with spaces are now trimmed first, accepted in Phase 1b. Missing issuer or subject is refused, as before.
 */
@Component
public class TrustedActorResolver {

    public ActorId resolve(Jwt jwt) {
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        String subject = jwt.getSubject();
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new SecurityContextException("Authenticated identity is missing issuer or subject", "MISSING_ISSUER_OR_SUBJECT");
        }
        return ActorId.of(StableIdentityId.fromIssuerAndSubject(issuer, subject));
    }
}
