package com.securedoc.platform.api.security.oidc;

import com.documentplatform.identity.StableIdentityId;
import com.securedoc.platform.api.context.SecurityContextException;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 1b P3: actor ids are stored in audit records, so the library's StableIdentityId must give exactly the ids
 * LexaVault's TrustedActorResolver gave before it was replaced. {@link #legacyActorId} is an independent copy of the
 * pre-0.3.0 TrustedActorResolver algorithm (no trimming), kept here as the reference.
 */
class ActorIdLibraryParityTest {

    private static final String PHASE1_ISSUER = "http://localhost:18181/realms/lexavault-phase1";

    /** The pre-0.3.0 algorithm: SHA-256 of issuer + "|" + subject, as a name-based UUID, without trimming. */
    static UUID legacyActorId(String issuer, String subject) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest((issuer + "|" + subject).getBytes(StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.wrap(digest);
        long msb = buffer.getLong();
        long lsb = buffer.getLong();
        msb = (msb & 0xffffffffffff0fffL) | 0x0000000000005000L;
        lsb = (lsb & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    private static Jwt token(String issuer, String subject) {
        return Jwt.withTokenValue("t").header("alg", "none").issuer(issuer).subject(subject).build();
    }

    @Test
    void libraryGivesTheIdenticalIdForRealisticIssuerAndSubjectPairs() throws Exception {
        List<String[]> pairs = List.of(
                // The four Phase 1 users (Keycloak sub is the user id).
                new String[] {PHASE1_ISSUER, "4d9aa90d-6e59-44f4-b768-4771f6b7880b"},
                new String[] {PHASE1_ISSUER, "7f29af86-8043-411d-b146-0b4f32f8d3b6"},
                new String[] {PHASE1_ISSUER, "a2cd29b4-ae5d-46e4-a3a5-6a9e81faecda"},
                new String[] {PHASE1_ISSUER, "f7fb66cf-3cb9-4876-8d9e-29f9a7d8385f"},
                // Other realistic forms: Okta, Entra ID, Keycloak with a path, opaque subjects.
                new String[] {"https://acme.okta.com/oauth2/default", "00u1abcdEFGHijkl2345"},
                new String[] {"https://login.microsoftonline.com/9188040d-6c67-4c5b-b112-36a304b66dad/v2.0", "AAAAAAAAAAAAAAAAAAAAAIkzqFVrSaSaFHy782bbtaQ"},
                new String[] {"https://identity.example.test/realms/shared", "subject-123"},
                new String[] {"https://idp.example.test/realms/acme", "user-1"},
                new String[] {"http://localhost:8181/realms/document-platform-local", "platform.ops.admin"},
                new String[] {"https://idp.example.test/", "service-account-intake"});
        for (String[] pair : pairs) {
            UUID expected = legacyActorId(pair[0], pair[1]);
            assertEquals(expected, StableIdentityId.fromIssuerAndSubject(pair[0], pair[1]), "library id for " + pair[0] + " | " + pair[1]);
            assertEquals(expected, new TrustedActorResolver().resolve(token(pair[0], pair[1])).value(), "resolver id for " + pair[0] + " | " + pair[1]);
        }
    }

    @Test
    void theGoldenIdFromTheLibraryIsUnchanged() throws Exception {
        UUID golden = UUID.fromString("ee47d7f1-9c8a-56ef-a2c1-bb219cdbb812");
        assertEquals(golden, legacyActorId("https://idp.example.test/realms/acme", "user-1"));
        assertEquals(golden, new TrustedActorResolver().resolve(token("https://idp.example.test/realms/acme", "user-1")).value());
    }

    @Test
    void theOneAcceptedDifference_valuesPaddedWithSpacesAreTrimmedByTheLibrary() throws Exception {
        // Accepted in task 0012: no customer is installed, so no stored audit history is affected. Real tokens carry
        // no such spaces. Before 0.3.0 a padded subject produced a different id; now it produces the unpadded id.
        String issuer = "https://idp.example.test/realms/acme";
        assertNotEquals(legacyActorId(issuer, " user-1 "), legacyActorId(issuer, "user-1"));
        assertEquals(legacyActorId(issuer, "user-1"), StableIdentityId.fromIssuerAndSubject(issuer, " user-1 "));
    }

    @Test
    void missingIssuerOrSubjectIsStillRefusedWithTheSameMessage() {
        Jwt noSubject = Jwt.withTokenValue("t").header("alg", "none").issuer(PHASE1_ISSUER).claim("k", "v").build();
        SecurityContextException refused = assertThrows(SecurityContextException.class, () -> new TrustedActorResolver().resolve(noSubject));
        assertEquals("Authenticated identity is missing issuer or subject", refused.getMessage());
        Jwt noIssuer = Jwt.withTokenValue("t").header("alg", "none").subject("s").build();
        assertThrows(SecurityContextException.class, () -> new TrustedActorResolver().resolve(noIssuer));
    }
}
