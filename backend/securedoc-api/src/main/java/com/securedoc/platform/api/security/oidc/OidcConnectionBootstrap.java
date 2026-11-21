package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.api.security.SecurityMode;
import com.securedoc.platform.api.security.SecurityProperties;
import com.securedoc.platform.infrastructure.identity.JdbcOidcConnectionBindingRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.UUID;

/** One-way compatibility bootstrap: records the already-trusted OIDC environment binding if no ACTIVE persisted connection exists. */
@Component
public class OidcConnectionBootstrap implements ApplicationRunner {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OidcConnectionBootstrap.class);
    private final SecurityProperties properties; private final JdbcOidcConnectionBindingRepository repository;
    public OidcConnectionBootstrap(SecurityProperties properties, JdbcOidcConnectionBindingRepository repository) { this.properties=properties; this.repository=repository; }
    @Override public void run(ApplicationArguments args) {
        if (properties.getMode()!= SecurityMode.OIDC || !properties.getOidc().isPersistentConnectionEnabled()) return;
        var o=properties.getOidc();
        if(o.getTenantId().isBlank()||o.getIssuerUri().isBlank()||o.getAudience().isBlank()) {
            // Task 0023 C7 (#348): say why nothing was bootstrapped (setting names only, never their values).
            log.warn("OIDC_CONNECTION_BOOTSTRAP_SKIPPED reason=SETTINGS_MISSING missing={}", missing(o));
            return;
        }
        repository.bootstrapIfAbsent(UUID.fromString(o.getTenantId()), o.getProviderId(), URI.create(o.getIssuerUri()), o.getBrowserClientId(), o.getAudience());
    }

    static String missing(SecurityProperties.Oidc o) {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (o.getTenantId().isBlank()) names.add("tenant-id");
        if (o.getIssuerUri().isBlank()) names.add("issuer-uri");
        if (o.getAudience().isBlank()) names.add("audience");
        return String.join(",", names);
    }
}
