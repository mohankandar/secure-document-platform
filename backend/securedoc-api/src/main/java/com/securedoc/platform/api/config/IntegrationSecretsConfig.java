package com.securedoc.platform.api.config;

import com.securedoc.platform.api.notifications.AppSecretResolver;
import com.securedoc.platform.application.port.out.SecretReferenceRule;
import com.securedoc.platform.infrastructure.secrets.IntegrationSecrets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hardening: secret references limited to approved names. A connection's secret reference follows the same rule as every
 * other secret the app reads ({@link AppSecretResolver}): only this entity's approved names; anything else is refused on
 * save and at each use.
 */
@Configuration
public class IntegrationSecretsConfig {

    @Bean
    SecretReferenceRule secretReferenceRule() {
        return AppSecretResolver::entityRef;
    }

    @Bean
    IntegrationSecrets integrationSecrets(SecretReferenceRule rule, AppSecretResolver resolver) {
        return new IntegrationSecrets(rule, resolver);
    }
}
