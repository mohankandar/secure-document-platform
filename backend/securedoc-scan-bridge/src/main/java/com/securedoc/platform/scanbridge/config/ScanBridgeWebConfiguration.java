package com.securedoc.platform.scanbridge.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ScanBridgeWebConfiguration implements WebMvcConfigurer {
    private final ScanBridgeProperties properties;

    public ScanBridgeWebConfiguration(ScanBridgeProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (properties.getAllowedOrigins().isEmpty()) return;
        registry.addMapping("/api/v1/**")
                .allowedOrigins(properties.getAllowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", BridgeRequestSecurityFilter.REQUEST_TOKEN_HEADER)
                .allowCredentials(false)
                .maxAge(600);
    }
}
