package com.securedoc.platform.api.security.oidc;

import com.securedoc.platform.infrastructure.identity.JdbcOidcConnectionBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Explicit one-shot operational recovery for an OIDC lockout.
 * Disabled by default. The request id is persisted in immutable identity history so the same recovery request
 * cannot be re-applied on a later restart if the environment variable is accidentally left enabled.
 */
@Component
@ConditionalOnProperty(name="securedoc.security.oidc.recovery.enabled", havingValue="true")
public class OidcKnownGoodRecovery implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(OidcKnownGoodRecovery.class);
    private final JdbcOidcConnectionBindingRepository repository;
    private final String tenantId,connectionId,requestId; private final long expectedVersion;

    public OidcKnownGoodRecovery(JdbcOidcConnectionBindingRepository repository,
            @Value("${securedoc.security.oidc.recovery.tenant-id:}") String tenantId,
            @Value("${securedoc.security.oidc.recovery.connection-id:}") String connectionId,
            @Value("${securedoc.security.oidc.recovery.expected-version:0}") long expectedVersion,
            @Value("${securedoc.security.oidc.recovery.request-id:}") String requestId) {
        this.repository=repository; this.tenantId=tenantId; this.connectionId=connectionId; this.expectedVersion=expectedVersion; this.requestId=requestId;
    }

    @Override public void run(ApplicationArguments args) {
        if(tenantId==null||tenantId.isBlank()||connectionId==null||connectionId.isBlank()||requestId==null||requestId.isBlank()||expectedVersion<=0)
            throw new IllegalStateException("OIDC recovery is enabled but tenant-id, connection-id, expected-version, and unique request-id are not fully configured.");
        var result=repository.recoverKnownGood(UUID.fromString(tenantId.trim()),UUID.fromString(connectionId.trim()),expectedVersion,requestId.trim());
        log.warn("ENTERPRISE_IDENTITY_RECOVERY_APPLIED tenantId={} connectionId={} version={} requestId={}",tenantId.trim(),result.active().connectionId(),result.active().configurationVersion(),requestId.trim());
    }
}
