package com.securedoc.platform.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// Task 0054: the outbound rules and the issuer fetch are OutboundRulesConfig's (the app does not carry
// platform-notifications, which platform's own wiring needs), and no LoginConnections bean is made: nothing in the app may
// change a login connection.
@SpringBootApplication(scanBasePackages = "com.securedoc.platform", excludeName = {
        "com.documentplatform.outbound.spring.OutboundAutoConfiguration",
        "com.documentplatform.identity.spring.IdentityIssuerFetchAutoConfiguration",
        "com.documentplatform.identity.spring.IdentityLoginConnectionsAutoConfiguration"})
@EntityScan(basePackages = "com.securedoc.platform.infrastructure.persistence.entity")
@EnableScheduling
public class SecureDocumentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(SecureDocumentPlatformApplication.class);
        // Task 0026: a stop at start logs its LexaVault code (LV-SYS-003, LV-SYS-005 or LV-AI-007).
        application.addListeners(new com.securedoc.platform.api.config.StartupFailureLogger());
        // Task 0045: an unknown securedoc.delivery.type stops the start in one sentence, before any bean is made.
        application.addInitializers(new com.securedoc.platform.api.config.DeliveryTypeCheck());
        application.run(args);
    }
}
