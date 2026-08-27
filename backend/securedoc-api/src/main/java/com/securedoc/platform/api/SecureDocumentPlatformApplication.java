package com.securedoc.platform.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@SpringBootApplication(scanBasePackages = "com.securedoc.platform")
@EntityScan(basePackages = "com.securedoc.platform.infrastructure.persistence.entity")
public class SecureDocumentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecureDocumentPlatformApplication.class, args);
    }
}
