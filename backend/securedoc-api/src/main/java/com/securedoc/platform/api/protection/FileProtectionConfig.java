package com.securedoc.platform.api.protection;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.envelope.StreamCipher;
import com.documentplatform.licensing.LicenceBuild;
import com.securedoc.platform.api.security.DevelopmentBuildOnly;
import com.securedoc.platform.infrastructure.storage.FileEncryption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Task 0060: every stored file is encrypted with the customer's keys. Without a key service the app does not start, except
 * in a LOCAL start of a development build (see {@link LocalProtectionDefaults}); a production build never stores plain files.
 */
@Configuration
public class FileProtectionConfig {
    private static final Logger log = LoggerFactory.getLogger(FileProtectionConfig.class);
    static final String NO_KEY_SERVICE = "The key service is not set up: set platform.crypto.vault.* "
            + "(docs/install/KEY_SERVICE.md). Stored documents are always encrypted; only a local development start of a "
            + "development build runs without it.";

    @Bean
    FileEncryption fileEncryption(Environment environment, ObjectProvider<StreamCipher> ciphers,
                                  ObjectProvider<CustomerId> customers) {
        if (plain(environment)) return FileEncryption.developmentPlain();
        StreamCipher cipher = ciphers.getIfUnique();
        CustomerId customer = customers.getIfUnique();
        if (cipher == null || customer == null) throw new IllegalStateException(NO_KEY_SERVICE);
        return FileEncryption.with(cipher, customer);
    }

    /** After every bean is made (so a refused licence is told first) and before anything is served. */
    @Bean
    SmartInitializingSingleton plainFilesOnlyInDevelopment(Environment environment, ObjectProvider<LicenceBuild> build) {
        return () -> {
            if (!plain(environment)) return;
            // The same check of what this build is as for LOCAL mode (hardening: LOCAL mode only in development builds).
            if (!DevelopmentBuildOnly.isDevelopmentBuild(build)) throw new IllegalStateException(NO_KEY_SERVICE);
            log.warn("DEVELOPMENT_ONLY_PLAIN_FILES stored documents are NOT encrypted: no key service is set up in this local "
                    + "development start. Never use real documents.");
        };
    }

    private static boolean plain(Environment environment) {
        return LocalProtectionDefaults.DEVELOPMENT_PLAIN.equals(environment.getProperty(LocalProtectionDefaults.MODE));
    }
}
