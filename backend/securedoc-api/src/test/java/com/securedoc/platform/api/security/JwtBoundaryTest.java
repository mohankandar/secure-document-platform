package com.securedoc.platform.api.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtBoundaryTest {
    private static final String ISSUER = "https://identity.example.test/oauth2/default";
    private static final String AUDIENCE = "securedoc-api";

    private RSAPublicKey publicKey;
    private RSAPrivateKey privateKey;
    private NimbusJwtDecoder decoder;

    @BeforeEach
    void setup() throws Exception {
        KeyPair pair = keyPair();
        publicKey = (RSAPublicKey) pair.getPublic();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), new OidcAudienceValidator(AUDIENCE)));
    }

    @Test
    void acceptsValidSignedToken() {
        String token = token(publicKey, privateKey, ISSUER, AUDIENCE,
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(300));
        assertDoesNotThrow(() -> decoder.decode(token));
    }

    @Test
    void rejectsWrongAudienceIssuerExpiredNotYetValidAndInvalidSignature() throws Exception {
        assertThrows(JwtException.class, () -> decoder.decode(token(publicKey, privateKey,
                ISSUER, "other-api", Instant.now().minusSeconds(5), Instant.now().plusSeconds(300))));
        assertThrows(JwtException.class, () -> decoder.decode(token(publicKey, privateKey,
                "https://wrong.example", AUDIENCE, Instant.now().minusSeconds(5), Instant.now().plusSeconds(300))));
        assertThrows(JwtException.class, () -> decoder.decode(token(publicKey, privateKey,
                ISSUER, AUDIENCE, Instant.now().minusSeconds(600), Instant.now().minusSeconds(300))));
        assertThrows(JwtException.class, () -> decoder.decode(token(publicKey, privateKey,
                ISSUER, AUDIENCE, Instant.now().plusSeconds(300), Instant.now().plusSeconds(600))));

        KeyPair other = keyPair();
        assertThrows(JwtException.class, () -> decoder.decode(token(
                (RSAPublicKey) other.getPublic(), (RSAPrivateKey) other.getPrivate(),
                ISSUER, AUDIENCE, Instant.now().minusSeconds(5), Instant.now().plusSeconds(300))));
    }

    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String token(RSAPublicKey signingPublic, RSAPrivateKey signingPrivate,
                         String issuer, String audience, Instant nbf, Instant exp) {
        RSAKey jwk = new RSAKey.Builder(signingPublic).privateKey(signingPrivate).keyID("test-key").build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(jwk)));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("user-123")
                .audience(List.of(audience))
                .notBefore(nbf)
                .issuedAt(nbf)
                .expiresAt(exp)
                .claim("tenant_id", "00000000-0000-0000-0000-000000000001")
                .claim("groups", List.of("securedoc-user"))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
