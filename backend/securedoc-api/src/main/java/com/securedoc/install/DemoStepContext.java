package com.securedoc.install;

import com.securedoc.platform.infrastructure.persistence.adapter.JpaBusinessIdentifierDefinitionRepositoryAdapter;
import com.securedoc.platform.infrastructure.persistence.adapter.JdbcRedactionCatalogStore;
import com.securedoc.platform.infrastructure.persistence.adapter.JpaIntegrationConnectionRepositoryAdapter;
import com.securedoc.platform.infrastructure.persistence.adapter.JpaTenantRedactionPolicyRepositoryAdapter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The demo step's own small Spring context (task 0037): the database, JPA and the three repository adapters it uses,
 * with the redaction catalog store the policy adapter needs (task 0062), nothing else. No web server, no scheduled jobs, no start-up runners, no Liquibase or Flyway (switched off in
 * {@link DemoStep}). It lives outside {@code com.securedoc.platform}, so the app never scans it.
 */
@Configuration
@EnableAutoConfiguration
@EntityScan(basePackages = "com.securedoc.platform.infrastructure.persistence.entity")
@EnableJpaRepositories(basePackages = "com.securedoc.platform.infrastructure.persistence.repository")
@Import({JpaBusinessIdentifierDefinitionRepositoryAdapter.class, JpaIntegrationConnectionRepositoryAdapter.class,
        JpaTenantRedactionPolicyRepositoryAdapter.class, JdbcRedactionCatalogStore.class})
class DemoStepContext {
}
