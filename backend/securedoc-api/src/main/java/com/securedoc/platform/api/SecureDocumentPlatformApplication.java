package com.securedoc.platform.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.securedoc.platform")
@EntityScan(basePackages = "com.securedoc.platform.infrastructure.persistence.entity")
@EnableScheduling
public class SecureDocumentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecureDocumentPlatformApplication.class, args);
    }
}
