import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * Task 0018 test tool. Makes a throwaway LexaVault test licence for one run of the compliance report check.
 *
 * <p>A new Ed25519 key pair is generated each run. The private key exists only in this process's memory and is
 * never written. The public key (not a secret) is written for the app's LOCAL-profile development-key override
 * ({@code SECUREDOC_LICENSING_PUBLIC_KEY}); the vendor key is never used or needed.
 *
 * <p>Usage: {@code java MakeTestLicence.java <tenantId> <customerName> <out-public-key.txt> <out-token.txt>}
 */
public class MakeTestLicence {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: MakeTestLicence <tenantId> <customerName> <publicKeyOut> <tokenOut>");
        }
        UUID tenantId = UUID.fromString(args[0]);
        String customer = args[1];
        if (!customer.matches("[A-Za-z0-9 &.,'-]{1,100}")) throw new IllegalArgumentException("plain customer name only");

        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String payload = "{\"schemaVersion\":1,\"licenseId\":\"" + UUID.randomUUID() + "\",\"tenantId\":\"" + tenantId
                + "\",\"customerName\":\"" + customer + "\",\"edition\":\"TEST\",\"issuedAt\":\""
                + now.minus(1, ChronoUnit.DAYS) + "\",\"expiresAt\":\"" + now.plus(1, ChronoUnit.DAYS)
                + "\",\"entitlements\":[\"DOCUMENT_PROCESSING\",\"REPORTING\"],\"limits\":{}}";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(payloadBytes);
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String token = "LV1." + url.encodeToString(payloadBytes) + "." + url.encodeToString(signer.sign());

        Files.writeString(Path.of(args[2]), Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                StandardCharsets.US_ASCII);
        Files.writeString(Path.of(args[3]), token, StandardCharsets.US_ASCII);
    }
}
