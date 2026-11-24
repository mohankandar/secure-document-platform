package com.securedoc.install;

import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceBuild;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.TrustedKeys;
import com.documentplatform.setup.SetupCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0054 decision 2 (platform-core 0.7.0, SEC-12): the licence keys come only from the build. The development key is in
 * the development build's files alone, and a build without it refuses the development-signed demo licence.
 */
class LicenceBuildTest {

    private static final Path FILES = DemoStepTest.REPO.resolve("backend/licence-build");

    @TempDir
    static Path work;

    /** No database: setup checks the licence before it reads any database setting. */
    private static String setupRefusal(LicenceBuild build) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        Path demo = DemoStepTest.REPO.resolve("install/demo");
        int exit = new SetupCommand(Map.of(), Clock.systemUTC(), build).run(List.of("plan",
                "--licence", demo.resolve("licence-demo.json").toString(), "--signin", demo.resolve("signin-demo.json").toString(),
                "--product", "LEXAVAULT", "--product-roles", DemoStepTest.REPO.resolve("install/product-roles.json").toString(),
                "--config-out", work.resolve("t.properties").toString(), "--run-log", work.resolve("runs.log").toString(),
                "--no-customer-keys"), print, print);
        assertEquals(2, exit);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aProductionBuildRefusesTheDevelopmentSignedLicence() {
        String refused = setupRefusal(TestBuild.production());
        assertTrue(refused.contains("SETUP_LICENCE_REFUSED") && refused.contains("LICENCE_UNKNOWN_KEY_ID"), refused);
        // Listing the development key is not enough: without development-keys=true it is dropped.
        refused = setupRefusal(TestBuild.productionListingTheDevelopmentKey());
        assertTrue(refused.contains("SETUP_LICENCE_REFUSED") && refused.contains("LICENCE_UNKNOWN_KEY_ID"), refused);
        // The development build gets past the licence, to the missing database settings.
        assertTrue(setupRefusal(TestBuild.development()).contains("SETUP_DB_SETTINGS_MISSING"));
    }

    @Test
    void aBuildWithOnlyTheDevelopmentKeyAndNoFlagIsNoBuild() {
        LicenceBuild.Invalid refused = assertThrows(LicenceBuild.Invalid.class,
                () -> LicenceBuild.of(TestBuild.developmentKeys(), false, Instant.now()));
        assertTrue(refused.getMessage().contains("development keys"), refused.getMessage());
    }

    @Test
    void setupAndTheDemoStepNoLongerTakeKeys() {
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertEquals(2, new SetupCommand(Map.of(), Clock.systemUTC(), TestBuild.development()).run(
                List.of("plan", "--keys", "k.json", "--run-log", work.resolve("runs.log").toString()), quiet, quiet));
        assertEquals(2, new DemoStep(quiet, quiet, Clock.systemUTC()).run(new String[] {"apply", "--keys", "k.json"},
                Map.of("LEXAVAULT_DEMO_DB_URL", "jdbc:postgresql://h/db", "LEXAVAULT_DEMO_DB_USER", "u",
                        "LEXAVAULT_DEMO_DB_PASSWORD", "p")));
    }

    @Test
    void theDemoStepRefusesABuildWithoutLicenceKeysBeforeItReadsTheDatabase() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(err, true, StandardCharsets.UTF_8);
        int exit = new DemoStep(print, print, Clock.systemUTC(),
                () -> LicenceBuild.of(TestBuild.developmentKeys(), false, Instant.now())).run(new String[] {"apply"},
                Map.of("LEXAVAULT_DEMO_DB_URL", "jdbc:postgresql://127.0.0.1:1/none", "LEXAVAULT_DEMO_DB_USER", "u",
                        "LEXAVAULT_DEMO_DB_PASSWORD", "p"));
        assertEquals(2, exit);
        assertTrue(err.toString(StandardCharsets.UTF_8).startsWith("LICENCE_BUILD_INVALID: "), err.toString(StandardCharsets.UTF_8));
    }

    /** DemoFilesGenerator signs by itself (the library only verifies): the library takes what it makes. */
    @Test
    void theGeneratorsSigningIsTheLibrarysFormat() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Licence licence = DemoFilesGenerator.demoLicence();
        LicenceVerifier verifier = new LicenceVerifier(TrustedKeys.of(Map.of(licence.keyId(), pair.getPublic()), Set.of(licence.keyId())));
        assertEquals(licence, verifier.verify(DemoFilesGenerator.sign(licence, pair.getPrivate()), licence.tenantId()));
    }

    @Test
    void theDevelopmentBuildShipsTheDemoLicencesKeyAndSaysItIsADevelopmentBuild() throws Exception {
        Path dev = FILES.resolve("dev/META-INF/platform");
        String keys = Files.readString(dev.resolve("licence-keys.json"));
        assertEquals(TrustedKeys.parse(Files.readString(DemoStepTest.REPO.resolve("install/demo/development-trusted-keys.json"))).toJson(),
                TrustedKeys.parse(keys).toJson(), "the same key in both files");
        assertTrue(TrustedKeys.parse(keys).isDevelopmentKey(DemoFilesGenerator.DEV_KEY_ID));
        assertTrue(Files.readString(dev.resolve("licence-build.properties")).lines().anyMatch("development-keys=true"::equals));
    }

    @Test
    void theProductionBuildIsTheDefaultAndCarriesNoDevelopmentKey() throws Exception {
        Path production = FILES.resolve("production/META-INF/platform");
        assertFalse(Files.readString(production.resolve("licence-build.properties")).contains("development-keys"));
        if (Files.exists(production.resolve("licence-keys.json"))) {
            TrustedKeys keys = TrustedKeys.parse(Files.readString(production.resolve("licence-keys.json")));
            assertFalse(keys.keyIds().contains(DemoFilesGenerator.DEV_KEY_ID));
            assertTrue(keys.keyIds().stream().noneMatch(keys::isDevelopmentKey));
        }
        String pom = Files.readString(DemoStepTest.REPO.resolve("backend/pom.xml"));
        assertTrue(pom.contains("<licence.build>production</licence.build>"), "production unless -Pdev-build");
        assertEquals(1, pom.split("<licence.build>dev</licence.build>", -1).length - 1, "only the dev-build profile");
        for (String module : List.of("securedoc-api", "securedoc-setup")) {
            assertTrue(Files.readString(DemoStepTest.REPO.resolve("backend/" + module + "/pom.xml"))
                    .contains("../licence-build/${licence.build}"), module);
        }
    }
}
