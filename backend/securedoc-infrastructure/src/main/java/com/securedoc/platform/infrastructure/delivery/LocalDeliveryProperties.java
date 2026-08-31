package com.securedoc.platform.infrastructure.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the local folder delivery destination.
 */
@ConfigurationProperties(prefix = "securedoc.delivery.local")
public class LocalDeliveryProperties {

    /** Root output directory for delivered redacted documents. */
    private String outputPath = "./securedoc-output";

    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }
}
