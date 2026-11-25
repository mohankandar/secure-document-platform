package com.securedoc.platform.api.protection;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Task 0060: what a LOCAL development start gets when nothing is set up for it, so that it starts without a key service:
 * files stored plain (refused by {@link FileProtectionConfig} unless this is a development build), and an erase log in the
 * person's own folder, one per database (also for the local OIDC start). Anywhere else nothing is filled in: the key service and the erase log location must
 * be configured, or the app does not start. Setting {@link #MODE} has no effect; it is always decided here.
 */
public final class LocalProtectionDefaults implements EnvironmentPostProcessor, Ordered {
    static final String MODE = "securedoc.storage.file-protection";
    static final String ENCRYPTED = "ENCRYPTED";
    static final String DEVELOPMENT_PLAIN = "DEVELOPMENT_PLAIN";
    static final String ERASE_LOG_LOCATION = "platform.erase-log.location";

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> decided = new HashMap<>();
        boolean plain = local(environment) && blank(environment.getProperty("platform.crypto.vault.address"));
        decided.put(MODE, plain ? DEVELOPMENT_PLAIN : ENCRYPTED);
        if (plain) decided.put("platform.crypto.enabled", "false");
        if (environment.acceptsProfiles(Profiles.of("local")) && blank(environment.getProperty(ERASE_LOG_LOCATION))) {
            decided.put(ERASE_LOG_LOCATION, localEraseLog(System.getProperty("user.home"),
                    environment.getProperty("spring.datasource.url", "")).toString());
        }
        environment.getPropertySources().addFirst(new MapPropertySource("lexavault-protection", decided));
    }

    static boolean local(org.springframework.core.env.Environment environment) {
        return environment.acceptsProfiles(Profiles.of("local"))
                && "LOCAL".equalsIgnoreCase(environment.getProperty("securedoc.security.mode", "OIDC").trim());
    }

    /** One folder per database, outside any storage folder: a database belongs to one erase log. */
    static Path localEraseLog(String home, String jdbcUrl) {
        String name = jdbcUrl.replaceFirst("[?].*", "").replaceFirst("^jdbc:[a-z]+:/*", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return Path.of(home, ".lexavault", "erase-log", name.isEmpty() ? "default" : name).toAbsolutePath().normalize();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
