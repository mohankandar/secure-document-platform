package com.securedoc.platform.infrastructure.config;

import com.securedoc.platform.infrastructure.delivery.LocalDeliveryProperties;
import com.securedoc.platform.infrastructure.storage.LocalStorageProperties;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrProperties;
import com.securedoc.platform.infrastructure.intake.WatchedFolderProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = "com.securedoc.platform.infrastructure.persistence.repository")
@EnableConfigurationProperties({LocalStorageProperties.class, LocalDeliveryProperties.class, TesseractOcrProperties.class, WatchedFolderProperties.class})
public class InfrastructureConfig {
}
