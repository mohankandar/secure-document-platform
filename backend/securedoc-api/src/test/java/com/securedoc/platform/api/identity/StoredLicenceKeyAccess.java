package com.securedoc.platform.api.identity;

import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.licensing.LicenceBuild;
import com.documentplatform.licensing.jdbc.PostgresLicenceStore;

import javax.sql.DataSource;
import java.util.function.Supplier;

/** For the install tests: the app's {@link StoredLicenceKey} with a given licence build. */
public final class StoredLicenceKeyAccess {

    private StoredLicenceKeyAccess() {
    }

    public static DevelopmentLicence of(DataSource dataSource, Supplier<LicenceBuild> build) {
        return new StoredLicenceKey(new PostgresLicenceStore(dataSource), build);
    }
}
