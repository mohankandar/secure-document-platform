package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.domain.identity.ActorId;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

@Component
public class TrustedActorResolver {

    public ActorId resolve(Jwt jwt) {
        String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
        String subject = jwt.getSubject();
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new SecurityContextException("Authenticated identity is missing issuer or subject");
        }
        return ActorId.of(stableUuid(issuer + "|" + subject));
    }

    static UUID stableUuid(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.wrap(digest);
            long msb = buffer.getLong();
            long lsb = buffer.getLong();
            msb = (msb & 0xffffffffffff0fffL) | 0x0000000000005000L;
            lsb = (lsb & 0x3fffffffffffffffL) | 0x8000000000000000L;
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
