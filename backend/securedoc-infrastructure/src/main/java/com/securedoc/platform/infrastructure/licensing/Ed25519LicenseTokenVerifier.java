package com.securedoc.platform.infrastructure.licensing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.securedoc.platform.application.licensing.LicenseTokenVerifier;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.licensing.LicenseClaims;
import com.securedoc.platform.domain.licensing.LicenseValidationStatus;
import com.securedoc.platform.domain.licensing.LicenseVerificationResult;
import com.securedoc.platform.domain.licensing.TenantLicenseRecord;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Verifies compact LexaVault license tokens: LV1.&lt;base64url(payload-json)&gt;.&lt;base64url(ed25519-signature)&gt;.
 * The private signing key is never part of the runtime application.
 */
public final class Ed25519LicenseTokenVerifier implements LicenseTokenVerifier {
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;
    private final PublicKey publicKey;

    public Ed25519LicenseTokenVerifier(String configuredPublicKey) {
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        this.publicKey = parsePublicKey(configuredPublicKey);
    }

    @Override
    public LicenseVerificationResult verify(String signedToken, TenantId expectedTenantId, Instant now) {
        if (publicKey == null) {
            return LicenseVerificationResult.of(LicenseValidationStatus.CONFIGURATION_ERROR, null,
                    "LICENSE_VERIFICATION_KEY_NOT_CONFIGURED");
        }
        if (signedToken == null || signedToken.isBlank()) {
            return LicenseVerificationResult.of(LicenseValidationStatus.MALFORMED, null, "LICENSE_TOKEN_EMPTY");
        }
        try {
            String[] parts = signedToken.trim().split("\\.", -1);
            if (parts.length != 3 || !"LV1".equals(parts[0])) {
                return LicenseVerificationResult.of(LicenseValidationStatus.MALFORMED, null,
                        "LICENSE_TOKEN_FORMAT_INVALID");
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            byte[] signatureBytes = Base64.getUrlDecoder().decode(parts[2]);

            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey);
            signature.update(payload);
            if (!signature.verify(signatureBytes)) {
                return LicenseVerificationResult.of(LicenseValidationStatus.INVALID_SIGNATURE, null,
                        "LICENSE_SIGNATURE_INVALID");
            }

            LicenseClaims claims = parseClaims(payload);
            if (claims.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
                return LicenseVerificationResult.of(LicenseValidationStatus.UNSUPPORTED_SCHEMA, claims,
                        "LICENSE_SCHEMA_UNSUPPORTED");
            }
            if (!claims.tenantId().equals(expectedTenantId)) {
                return LicenseVerificationResult.of(LicenseValidationStatus.TENANT_MISMATCH, claims,
                        "LICENSE_TENANT_MISMATCH");
            }
            if (claims.notBefore() != null && now.isBefore(claims.notBefore())) {
                return LicenseVerificationResult.of(LicenseValidationStatus.NOT_YET_VALID, claims,
                        "LICENSE_NOT_YET_VALID");
            }
            if (!now.isBefore(claims.expiresAt())) {
                return LicenseVerificationResult.of(LicenseValidationStatus.EXPIRED, claims,
                        "LICENSE_EXPIRED");
            }
            return LicenseVerificationResult.of(LicenseValidationStatus.ACTIVE, claims, "LICENSE_ACTIVE");
        } catch (Exception e) {
            return LicenseVerificationResult.of(LicenseValidationStatus.MALFORMED, null,
                    "LICENSE_TOKEN_MALFORMED");
        }
    }

    @Override
    public boolean isVerificationKeyConfigured() {
        return publicKey != null;
    }

    @Override
    public Optional<LicenseClaims> readPersistedVerifiedClaims(TenantLicenseRecord record) {
        if (record == null) return Optional.empty();
        try {
            String[] parts = record.signedToken().trim().split("\\.", -1);
            if (parts.length != 3 || !"LV1".equals(parts[0])) return Optional.empty();
            if (!sha256(parts[1]).equalsIgnoreCase(record.payloadSha256())) return Optional.empty();

            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            LicenseClaims claims = parseClaims(payload);
            if (claims.schemaVersion() != SUPPORTED_SCHEMA_VERSION) return Optional.empty();
            if (!claims.tenantId().equals(record.tenantId())) return Optional.empty();
            if (!claims.licenseId().equals(record.licenseId())) return Optional.empty();
            return Optional.of(claims);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private LicenseClaims parseClaims(byte[] payloadBytes) throws Exception {
        JsonNode node = objectMapper.readTree(payloadBytes);
        int schemaVersion = requiredInt(node, "schemaVersion");
        UUID licenseId = UUID.fromString(requiredText(node, "licenseId"));
        TenantId tenantId = TenantId.of(UUID.fromString(requiredText(node, "tenantId")));
        String customerName = optionalText(node, "customerName");
        String edition = requiredText(node, "edition");
        Instant issuedAt = Instant.parse(requiredText(node, "issuedAt"));
        Instant notBefore = optionalInstant(node, "notBefore");
        Instant expiresAt = Instant.parse(requiredText(node, "expiresAt"));
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }

        Set<String> entitlements = new LinkedHashSet<>();
        JsonNode entitlementsNode = node.get("entitlements");
        if (entitlementsNode != null && entitlementsNode.isArray()) {
            for (JsonNode entitlement : entitlementsNode) {
                if (entitlement.isTextual() && !entitlement.textValue().isBlank()) {
                    entitlements.add(entitlement.textValue().trim().toUpperCase());
                }
            }
        }

        Map<String, Long> limits = Map.of();
        JsonNode limitsNode = node.get("limits");
        if (limitsNode != null && limitsNode.isObject()) {
            limits = objectMapper.convertValue(limitsNode, new TypeReference<Map<String, Long>>() {});
        }
        return new LicenseClaims(schemaVersion, licenseId, tenantId, customerName, edition,
                issuedAt, notBefore, expiresAt, entitlements, limits);
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return value.textValue().trim();
    }

    private static int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt()) throw new IllegalArgumentException("Missing " + field);
        return value.intValue();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText(null);
    }

    private static Instant optionalInstant(JsonNode node, String field) {
        String text = optionalText(node, field);
        return text == null || text.isBlank() ? null : Instant.parse(text);
    }

    private static PublicKey parsePublicKey(String configured) {
        if (configured == null || configured.isBlank()) return null;
        try {
            String normalized = configured.trim()
                    .replace("\\n", "\n")
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] encoded = Base64.getDecoder().decode(normalized.getBytes(StandardCharsets.US_ASCII));
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception e) {
            return null;
        }
    }
}
