package com.securedoc.platform.scanbridge;

import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ScanBridgeProperties.class)
public class ScanBridgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScanBridgeApplication.class, args);
    }
}
