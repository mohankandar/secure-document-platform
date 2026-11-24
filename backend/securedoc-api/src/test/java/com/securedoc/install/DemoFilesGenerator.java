package com.securedoc.install;

import com.documentplatform.licensing.Allowance;
import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.Deployment;
import com.documentplatform.licensing.Ed25519;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceFormat;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceType;
import com.documentplatform.licensing.SignedDocument;
import com.documentplatform.licensing.TrustedKeys;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import com.securedoc.platform.infrastructure.identity.LexaVaultProduct;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Makes LexaVault's demo licence and its development trusted-keys file in {@code install/demo/} (task 0037). Runs only
 * when asked, from {@code backend/securedoc-api}:
 *
 * <pre>mvn test -Dtest=DemoFilesGenerator -Dlexavault.demo.regenerate=true</pre>
 *
 * A new development key pair is made each time; its private key is thrown away after signing, so it exists nowhere.
 * Only the public key is written, to {@code development-trusted-keys.json}, which no production installation lists:
 * a production install refuses the demo licence ({@code LICENCE_UNKNOWN_KEY_ID}). The licence is NON_PRODUCTION
 * (the app shows "Test environment") and perpetual, so it never needs signing again.
 */
class DemoFilesGenerator {

    static final String DEV_KEY_ID = "dev-lexavault-demo-1";
    static final String TENANT_ID = "TEN-DEMO-0001";
    static final List<String> SLOTS = List.of("ENT-DEMO-0001", "ENT-DEMO-0002");

    @Test
    @EnabledIfSystemProperty(named = "lexavault.demo.regenerate", matches = "true")
    void writeDemoLicence() throws Exception {
        Path demo = Path.of("..", "..", "install", "demo").normalize();
        Files.createDirectories(demo);
        KeyPair dev = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Licence licence = demoLicence();
        Files.writeString(demo.resolve("licence-demo.json"), sign(licence, dev.getPrivate()), StandardCharsets.UTF_8);
        // Task 0041: marked as a development key, so setup takes an http issuer on localhost under this licence.
        String keys = TrustedKeys.of(Map.of(DEV_KEY_ID, dev.getPublic()), Set.of(DEV_KEY_ID)).toJson();
        Files.writeString(demo.resolve("development-trusted-keys.json"), keys, StandardCharsets.UTF_8);
        // Task 0054: the copy that the development build ships inside its jars.
        Files.writeString(Path.of("..", "licence-build", "dev", "META-INF", "platform", "licence-keys.json"), keys,
                StandardCharsets.UTF_8);
    }

    static Licence demoLicence() {
        List<Allowance> allowance = List.of(new Allowance(LexaVaultProduct.CODE, AllowanceType.PAGES_PER_MONTH, 1000));
        return new Licence("LIC-LV-DEMO-0001", 1, "LexaVault Demo Customer", TENANT_ID,
                List.of(LexaVaultProduct.CODE),
                SLOTS.stream().map(slot -> new EntitySlot(slot, allowance)).toList(),
                LicenceType.PERPETUAL, LicenceKind.NON_PRODUCTION, LocalDate.parse("2026-01-01"), null,
                LocalDate.parse("2036-12-31"), Deployment.ON_PREM, DEV_KEY_ID, Instant.parse("2026-11-22T00:00:00Z"));
    }

    /**
     * Task 0054: platform-licensing only verifies since 0.6.0 (signing is in platform-licensing-issue, which LexaVault does
     * not carry), so the demo licence is signed here: the same message, the same file.
     */
    static String sign(Licence licence, PrivateKey key) throws Exception {
        Map<String, Object> payload = LicenceFormat.payload(licence);
        byte[] message = SignedDocument.signedMessage(LicenceFormat.FORMAT, payload);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key);
        signature.update(message);
        Map<String, Object> file = new TreeMap<>();
        file.put("format", LicenceFormat.FORMAT);
        file.put("licence", payload);
        file.put("signature", new TreeMap<>(Map.of("algorithm", Ed25519.ALGORITHM, "payloadSha256", Ed25519.sha256Hex(message),
                "value", Base64.getEncoder().encodeToString(signature.sign()))));
        return JsonMapper.builderWithJackson2Defaults().enable(SerializationFeature.INDENT_OUTPUT)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build().writeValueAsString(file) + "\n";
    }
}
