import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

public final class GenerateLexaVaultNoProcessingLicense {
    public static void main(String[] args) throws Exception {
        UUID tenantId = UUID.fromString(args[0]);

        Instant issued = Instant.now();
        Instant expires = issued.plus(7, ChronoUnit.DAYS);
        UUID licenseId = UUID.randomUUID();

        var generator = KeyPairGenerator.getInstance("Ed25519");
        var keyPair = generator.generateKeyPair();

        String json = "{" +
                "\"schemaVersion\":1," +
                "\"licenseId\":\"" + licenseId + "\"," +
                "\"tenantId\":\"" + tenantId + "\"," +
                "\"customerName\":\"LOCAL ENTITLEMENT TEST\"," +
                "\"edition\":\"DEVELOPMENT\"," +
                "\"issuedAt\":\"" + issued + "\"," +
                "\"expiresAt\":\"" + expires + "\"," +
                "\"entitlements\":[" +
                    "\"LOCAL_AI\"," +
                    "\"DERIVED_ROUTING\"," +
                    "\"WATCHED_FOLDER_INTAKE\"," +
                    "\"REPORTING\"," +
                    "\"SCANNER_INTAKE\"" +
                "]," +
                "\"limits\":{}" +
                "}";

        byte[] payload = json.getBytes(StandardCharsets.UTF_8);

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload);

        String publicKey =
                Base64.getEncoder().encodeToString(
                        keyPair.getPublic().getEncoded());

        String token =
                "LV1." +
                Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(payload) +
                "." +
                Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(signer.sign());

        System.out.println("PUBLIC_KEY=" + publicKey);
        System.out.println("LICENSE_TOKEN=" + token);
        System.out.println("LICENSE_ID=" + licenseId);
        System.out.println("EXPIRES_AT=" + expires);
    }
}