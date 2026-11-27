package com.securedoc.platform.api.security;

import com.documentplatform.licensing.LicenceBuild;
import com.securedoc.platform.domain.error.LvErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

/**
 * Hardening: LOCAL mode only in development builds. The one check of what this build is, for everything that a production
 * build must never do (LOCAL mode, plain stored files). What a build is, is inside its own jar (task 0054): a development
 * build ({@code -Pdev-build}) trusts the development licence key, a production build does not. A test gives its own
 * {@link LicenceBuild} bean.
 */
public final class DevelopmentBuildOnly {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentBuildOnly.class);
    static final String MODE = "securedoc.security.mode";
    public static final String LOCAL_MODE_REFUSED = "LOCAL mode is only available in development builds";

    private DevelopmentBuildOnly() {
    }

    /** True only for a development build. */
    public static boolean isDevelopmentBuild(ObjectProvider<LicenceBuild> given) {
        LicenceBuild build = given == null ? null : given.getIfUnique();
        LicenceBuild thisBuild = build != null ? build : LicenceBuild.load();
        return thisBuild.keys().keyIds().stream().anyMatch(thisBuild.keys()::isDevelopmentKey);
    }

    /** LOCAL mode asked for, by the bound setting or by the raw one, whatever its case or spaces. */
    static boolean localModeAskedFor(SecurityProperties properties, Environment environment) {
        String raw = environment.getProperty(MODE);
        return properties.getMode() == SecurityMode.LOCAL || (raw != null && "LOCAL".equalsIgnoreCase(raw.strip()));
    }

    /**
     * Refuses the start of a production build that asks for LOCAL mode, whatever else is set (a key service or not).
     *
     * @throws IllegalStateException with {@link #LOCAL_MODE_REFUSED}
     */
    static void refuseLocalModeOutsideDevelopment(SecurityProperties properties, Environment environment,
                                                  ObjectProvider<LicenceBuild> build) {
        if (!localModeAskedFor(properties, environment) || isDevelopmentBuild(build)) return;
        log.error("LV_LOCAL_MODE_REFUSED code={} {}", LvErrorCode.SYS_005.code(), LOCAL_MODE_REFUSED);
        throw new IllegalStateException(LvErrorCode.SYS_005.code() + " " + LOCAL_MODE_REFUSED);
    }
}
