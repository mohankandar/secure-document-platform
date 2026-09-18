import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * DEVELOPMENT/TEST ONLY. Generates an ephemeral Ed25519 key pair plus a signed LV1 tenant license.
 * The production private signing key must live only in LexaVault-controlled licensing infrastructure.
 */
public final class GenerateLexaVaultDevelopmentLicense {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java GenerateLexaVaultDevelopmentLicense <tenant-uuid> [days-valid]");
            System.exit(2);
        }
        UUID tenantId = UUID.fromString(args[0]);
        long days = args.length > 1 ? Long.parseLong(args[1]) : 7L;
        Instant issued = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = issued.plus(days, ChronoUnit.DAYS);
        UUID licenseId = UUID.randomUUID();

        var generator = KeyPairGenerator.getInstance("Ed25519");
        var keyPair = generator.generateKeyPair();

        String json = "{" +
                "\"schemaVersion\":1," +
                "\"licenseId\":\"" + licenseId + "\"," +
                "\"tenantId\":\"" + tenantId + "\"," +
                "\"customerName\":\"LOCAL DEVELOPMENT\"," +
                "\"edition\":\"DEVELOPMENT\"," +
                "\"issuedAt\":\"" + issued + "\"," +
                "\"expiresAt\":\"" + expires + "\"," +
                "\"entitlements\":[\"DOCUMENT_PROCESSING\",\"SCANNER_INTAKE\",\"WATCHED_FOLDER_INTAKE\",\"REPORTING\",\"DERIVED_ROUTING\",\"LOCAL_AI\"]," +
                "\"limits\":{}" +
                "}";
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload);
        byte[] signature = signer.sign();

        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String token = "LV1." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        System.out.println("# DEVELOPMENT ONLY - ephemeral key pair");
        System.out.println("PUBLIC_KEY=" + publicKey);
        System.out.println("LICENSE_TOKEN=" + token);
        System.out.println("LICENSE_ID=" + licenseId);
        System.out.println("EXPIRES_AT=" + expires);
    }
}
