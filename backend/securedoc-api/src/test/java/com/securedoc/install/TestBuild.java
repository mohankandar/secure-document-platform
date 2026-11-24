package com.securedoc.install;

import com.documentplatform.licensing.LicenceBuild;
import com.documentplatform.licensing.TrustedKeys;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;

/**
 * Licence builds for the tests (task 0054): given to setup and the demo step, so no test depends on the -Pdev-build
 * profile or on what is on the classpath.
 */
public final class TestBuild {

    private static final Instant BUILT_AT = Instant.parse("2026-11-01T00:00:00Z");

    private TestBuild() {
    }

    static TrustedKeys developmentKeys() {
        try {
            return TrustedKeys.parse(Files.readString(DemoStepTest.REPO.resolve("install/demo/development-trusted-keys.json")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** As -Pdev-build: trusts the demo licence's key. */
    public static LicenceBuild development() {
        return LicenceBuild.of(developmentKeys(), true, BUILT_AT);
    }

    /** A production build: one production key, made here. */
    public static LicenceBuild production() {
        return LicenceBuild.of(TrustedKeys.of(Map.of("lic-test-production", newKey())), false, BUILT_AT);
    }

    /** A production build whose keys file also lists the development key: the key is not trusted. */
    static LicenceBuild productionListingTheDevelopmentKey() {
        String dev = DemoFilesGenerator.DEV_KEY_ID;
        TrustedKeys both = TrustedKeys.of(Map.of("lic-test-production", newKey(), dev, developmentKeys().key(dev).orElseThrow()),
                java.util.Set.of(dev));
        return LicenceBuild.of(both, false, BUILT_AT);
    }

    private static java.security.PublicKey newKey() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
