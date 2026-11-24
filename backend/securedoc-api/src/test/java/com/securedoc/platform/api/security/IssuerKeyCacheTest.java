package com.securedoc.platform.api.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Task 0056: the signing keys through a real decoder and a key address that answers, fails and changes. */
class IssuerKeyCacheTest {

    private final AtomicLong clock = new AtomicLong();
    private final AtomicInteger fetches = new AtomicInteger();
    private final AtomicBoolean down = new AtomicBoolean();
    private volatile String keys;
    private HttpServer server;
    private RSAKey first;
    private RSAKey second;
    private JwtDecoder decoder;

    @BeforeEach
    void start() throws Exception {
        first = key("first");
        second = key("second");
        keys = new JWKSet(first.toPublicJWK()).toString();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/keys", exchange -> {
            fetches.incrementAndGet();
            byte[] body = keys.getBytes(StandardCharsets.UTF_8);
            if (down.get()) {
                exchange.sendResponseHeaders(503, -1);
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        decoder = NimbusJwtDecoder.withJwkSetUri("http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort() + "/keys").cache(new IssuerKeyCache(clock::get)).build();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void theKeysAreFetchedAgainAfterFiveMinutes() {
        decode(first);
        decode(first);
        assertEquals(1, fetches.get());
        pass(Duration.ofMinutes(5));
        decode(first);
        assertEquals(2, fetches.get());
    }

    @Test
    void anOutageIsBridgedForFifteenMinutesAndNoLonger() {
        decode(first);
        down.set(true);
        pass(Duration.ofMinutes(14));
        decode(first);
        pass(Duration.ofMinutes(1));
        JwtException refused = assertThrows(JwtException.class, () -> decoder.decode(token(first)));
        assertFalse(refused instanceof BadJwtException, "the keys are unavailable; the token is not called bad");
        down.set(false);
        decode(first);
    }

    @Test
    void anUnknownKeyIsLookedUpAtOnceAndDoesNotDropTheOthers() {
        decode(first);
        keys = new JWKSet(java.util.List.of(first.toPublicJWK(), second.toPublicJWK())).toString();
        decode(second);
        assertEquals(2, fetches.get());

        down.set(true);
        assertThrows(JwtException.class, () -> decoder.decode(token(keyQuietly("third"))));
        decode(first);
        down.set(false);
        assertInstanceOf(BadJwtException.class,
                assertThrows(JwtException.class, () -> decoder.decode(token(keyQuietly("fourth")))));
        decode(second);
    }

    private void decode(RSAKey key) {
        assertDoesNotThrow(() -> decoder.decode(token(key)));
    }

    private void pass(Duration time) {
        clock.addAndGet(time.toNanos());
    }

    private static RSAKey keyQuietly(String id) {
        try {
            return key(id);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey key(String id) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey(pair.getPrivate()).keyID(id).build();
    }

    private static String token(RSAKey key) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build(),
                JwtClaimsSet.builder().subject("s").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build()))
                .getTokenValue();
    }
}
