package com.securedoc.platform.infrastructure.config;

import com.securedoc.platform.infrastructure.storage.LocalStorageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = "com.securedoc.platform.infrastructure.persistence.repository")
@EnableConfigurationProperties(LocalStorageProperties.class)
public class InfrastructureConfig {
}
