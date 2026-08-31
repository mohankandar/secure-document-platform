package com.securedoc.platform.infrastructure.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "securedoc.delivery.api")
public class ApiDeliveryProperties {
    private String endpoint;
    private String bearerToken;
    private int timeoutSeconds = 60;
    private boolean requireAuthentication = true;
    private boolean requireHttps = true;

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getBearerToken() { return bearerToken; }
    public void setBearerToken(String bearerToken) { this.bearerToken = bearerToken; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public boolean isRequireAuthentication() { return requireAuthentication; }
    public void setRequireAuthentication(boolean requireAuthentication) { this.requireAuthentication = requireAuthentication; }
    public boolean isRequireHttps() { return requireHttps; }
    public void setRequireHttps(boolean requireHttps) { this.requireHttps = requireHttps; }
}
