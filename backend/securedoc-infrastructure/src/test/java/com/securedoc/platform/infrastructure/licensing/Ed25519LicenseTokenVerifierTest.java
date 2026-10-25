package com.securedoc.platform.infrastructure.licensing;

import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.licensing.LicenseValidationStatus;
import com.securedoc.platform.domain.licensing.TenantLicenseRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class Ed25519LicenseTokenVerifierTest {

    @Test
    void verifiesTenantBoundEd25519TokenAndRejectsTampering() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        TenantId tenantId = TenantId.generate();
        Instant now = Instant.now();
        String payload = "{" +
                "\"schemaVersion\":1," +
                "\"licenseId\":\"" + UUID.randomUUID() + "\"," +
                "\"tenantId\":\"" + tenantId + "\"," +
                "\"customerName\":\"Test\"," +
                "\"edition\":\"ENTERPRISE\"," +
                "\"issuedAt\":\"" + now.minusSeconds(60) + "\"," +
                "\"expiresAt\":\"" + now.plusSeconds(3600) + "\"," +
                "\"entitlements\":[\"DOCUMENT_PROCESSING\"],\"limits\":{}" +
                "}";
        String token = sign(pair, payload);

        Ed25519LicenseTokenVerifier verifier = new Ed25519LicenseTokenVerifier(publicKey);
        var valid = verifier.verify(token, tenantId, now);
        assertEquals(LicenseValidationStatus.ACTIVE, valid.status());
        assertTrue(valid.claims().entitlements().contains("DOCUMENT_PROCESSING"));

        var mismatch = verifier.verify(token, TenantId.generate(), now);
        assertEquals(LicenseValidationStatus.TENANT_MISMATCH, mismatch.status());

        String tampered = tamperSignature(token);
        assertNotEquals(LicenseValidationStatus.ACTIVE, verifier.verify(tampered, tenantId, now).status());
    }


    @Test
    void recoversPreviouslyVerifiedHistoricalClaimsWhenCurrentKeyChanges() throws Exception {
        KeyPair originalPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair rotatedPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        TenantId tenantId = TenantId.generate();
        UUID licenseId = UUID.randomUUID();
        Instant now = Instant.now();
        String payload = "{" +
                "\"schemaVersion\":1," +
                "\"licenseId\":\"" + licenseId + "\"," +
                "\"tenantId\":\"" + tenantId + "\"," +
                "\"customerName\":\"Historical\"," +
                "\"edition\":\"ENTERPRISE\"," +
                "\"issuedAt\":\"" + now.minusSeconds(60) + "\"," +
                "\"expiresAt\":\"" + now.plusSeconds(3600) + "\"," +
                "\"entitlements\":[\"DOCUMENT_PROCESSING\"],\"limits\":{}" +
                "}";
        String token = sign(originalPair, payload);
        String payloadPart = token.split("\\.", -1)[1];
        String payloadSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payloadPart.getBytes(StandardCharsets.UTF_8)));
        TenantLicenseRecord record = new TenantLicenseRecord(UUID.randomUUID(), tenantId, licenseId, token,
                payloadSha256, UUID.randomUUID(), now);

        String rotatedPublicKey = Base64.getEncoder().encodeToString(rotatedPair.getPublic().getEncoded());
        Ed25519LicenseTokenVerifier verifier = new Ed25519LicenseTokenVerifier(rotatedPublicKey);

        assertEquals(LicenseValidationStatus.INVALID_SIGNATURE, verifier.verify(token, tenantId, now).status());
        var historicalClaims = verifier.readPersistedVerifiedClaims(record);
        assertTrue(historicalClaims.isPresent());
        assertEquals("ENTERPRISE", historicalClaims.orElseThrow().edition());
        assertEquals(licenseId, historicalClaims.orElseThrow().licenseId());
    }

    private static String tamperSignature(String token) {
        String[] parts = token.split("\\.", -1);
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        signature[0] ^= 0x01;
        return parts[0] + "." + parts[1] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    private static String sign(KeyPair pair, String payload) throws Exception {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(pair.getPrivate());
        signature.update(bytes);
        return "LV1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
    }
}
