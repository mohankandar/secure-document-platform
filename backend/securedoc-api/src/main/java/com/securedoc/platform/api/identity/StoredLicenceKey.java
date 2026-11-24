package com.securedoc.platform.api.identity;

import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.licensing.LicenceBuild;
import com.documentplatform.licensing.LicenceStore;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.TrustedKeys;
import com.documentplatform.licensing.jdbc.PostgresLicenceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Whether the stored licence is signed by a development key this build trusts (task 0054): only then may the test sign-in
 * call an {@code http} login system on this machine. Read only: the licence file is verified, nothing is counted, enforced
 * or written (the clock is not read either). A build that trusts no development key (every build but {@code -Pdev-build}),
 * a missing or refused licence, or a database that cannot be read answers no.
 */
@Component
public class StoredLicenceKey implements DevelopmentLicence {

    private static final Logger log = LoggerFactory.getLogger(StoredLicenceKey.class);

    private final LicenceStore store;
    private final TrustedKeys keys;

    @Autowired
    public StoredLicenceKey(DataSource dataSource) {
        this(new PostgresLicenceStore(dataSource), LicenceBuild::load);
    }

    StoredLicenceKey(LicenceStore store, Supplier<LicenceBuild> build) {
        this.store = store;
        this.keys = keys(build);
    }

    @Override
    public boolean signedByDevelopmentKey() {
        if (keys == null) return false;
        try {
            return store.get(LicenceStore.Slot.CURRENT)
                    .map(stored -> new LicenceVerifier(keys).verify(stored.file().getBytes(StandardCharsets.UTF_8), null))
                    .map(licence -> keys.isDevelopmentKey(licence.keyId()))
                    .orElse(false);
        } catch (RuntimeException refusedOrUnreadable) {
            return false;
        }
    }

    private static TrustedKeys keys(Supplier<LicenceBuild> build) {
        try {
            return build.get().keys();
        } catch (LicenceBuild.Invalid e) {
            log.info("LICENCE_BUILD_HAS_NO_KEYS: http login systems on this machine are not tested ({})", e.getMessage());
            return null;
        }
    }
}
