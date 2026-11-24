package com.securedoc.platform.api.security;

import com.documentplatform.identity.spring.RequiredAudienceValidator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.securedoc.platform.api.observability.RequestCorrelationFilter;
import com.securedoc.platform.api.testsupport.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0023 C5 (#252): a rejected token is logged with its reason category and the 401 carries a correlation id. The
 * failures come from a real Nimbus decoder with the same validators as {@code SecurityConfig.jwtDecoder}, wrapped as
 * Spring's {@code JwtAuthenticationProvider} wraps them.
 */
class BearerTokenRefusalEntryPointTest {
    private static final String ISSUER = "https://identity.example.test/oauth2/default";
    private static final String AUDIENCE = "securedoc-api";

    private KeyPair keys;
    private NimbusJwtDecoder decoder;

    @BeforeEach
    void setUp() throws Exception {
        keys = keyPair();
        decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), new RequiredAudienceValidator(AUDIENCE)));
    }

    @Test
    void eachRejectedTokenGetsItsReasonCategory() throws Exception {
        Instant now = Instant.now();
        assertEquals("EXPIRED", reasonFor(token(keys, ISSUER, AUDIENCE, now.minusSeconds(900), now.minusSeconds(600))));
        assertEquals("NOT_YET_VALID", reasonFor(token(keys, ISSUER, AUDIENCE, now.plusSeconds(600), now.plusSeconds(900))));
        assertEquals("ISSUER", reasonFor(token(keys, "https://wrong.example", AUDIENCE, now.minusSeconds(5), now.plusSeconds(300))));
        assertEquals("AUDIENCE", reasonFor(token(keys, ISSUER, "other-api", now.minusSeconds(5), now.plusSeconds(300))));
        assertEquals("SIGNATURE", reasonFor(token(keyPair(), ISSUER, AUDIENCE, now.minusSeconds(5), now.plusSeconds(300))));
        assertEquals("MALFORMED", reasonFor("not-a-jwt"));
        assertEquals("MALFORMED", reasonFor(token(keys, ISSUER, AUDIENCE, now.minusSeconds(5), now.plusSeconds(300), "at+jwt")));
    }

    @Test
    void noTokenAndUnavailableKeysHaveTheirOwnCategory() {
        assertEquals("NO_TOKEN", BearerTokenRefusalEntryPoint.reason(new InsufficientAuthenticationException("x")));
        assertEquals("KEYS_UNAVAILABLE", BearerTokenRefusalEntryPoint.reason(
                new AuthenticationServiceException("Couldn't retrieve remote JWK set")));
        assertEquals("MALFORMED", BearerTokenRefusalEntryPoint.reason(new InvalidBearerTokenException("bad")));
    }

    @Test
    void the401KeepsItsCodeAndTextAddsTheCorrelationIdAndNeverLogsTheToken() throws Exception {
        String token = token(keys, ISSUER, "other-api", Instant.now().minusSeconds(5), Instant.now().plusSeconds(300));
        UUID correlation = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.setAttribute(RequestCorrelationFilter.REQUEST_ATTRIBUTE_CORRELATION_ID, correlation);
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        ObjectMapper mapper = JsonMapper.builderWithJackson2Defaults().build();

        try (LogCapture logs = LogCapture.of(BearerTokenRefusalEntryPoint.class)) {
            new BearerTokenRefusalEntryPoint(mapper).commence(request, response, rejected(token));
            assertTrue(logs.text().contains("AUTHENTICATION_REJECTED reason=AUDIENCE method=GET correlation=" + correlation),
                    logs.text());
            assertFalse(logs.text().contains(token));
            assertFalse(logs.text().contains("person@example.test"));
        }
        assertEquals(401, response.getStatus());
        assertEquals(correlation.toString(), response.getHeader("X-Correlation-Id"));
        JsonNode body = mapper.readTree(response.getContentAsString());
        assertEquals("AUTHENTICATION_REQUIRED", body.path("errorCode").asString());
        assertEquals("Please sign in again", body.path("message").asString());
        assertEquals("LV-AUT-001", body.path("code").asString());
        assertEquals(correlation.toString(), body.path("correlationId").asString());
        assertFalse(response.getContentAsString().contains("audience"));
    }

    private String reasonFor(String token) {
        return BearerTokenRefusalEntryPoint.reason(rejected(token));
    }

    /** As JwtAuthenticationProvider: a BadJwtException becomes InvalidBearerTokenException with it as cause. */
    private AuthenticationException rejected(String token) {
        try {
            decoder.decode(token);
            fail("token should be rejected");
            return null;
        } catch (BadJwtException bad) {
            return new InvalidBearerTokenException(bad.getMessage(), bad);
        } catch (JwtException other) {
            return new AuthenticationServiceException(other.getMessage(), other);
        }
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String token(KeyPair signing, String issuer, String audience, Instant nbf, Instant exp) {
        return token(signing, issuer, audience, nbf, exp, null);
    }

    private static String token(KeyPair signing, String issuer, String audience, Instant nbf, Instant exp, String type) {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) signing.getPublic())
                .privateKey((RSAPrivateKey) signing.getPrivate()).keyID("test-key").build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(jwk)));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject("user-123").audience(List.of(audience))
                .notBefore(nbf).issuedAt(nbf).expiresAt(exp).claim("email", "person@example.test").build();
        JwsHeader.Builder builder = JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key");
        JwsHeader header = (type == null ? builder : builder.type(type)).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
