package com.securedoc.platform.infrastructure.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for S3-compatible package delivery. */
@ConfigurationProperties(prefix = "securedoc.delivery.s3")
public class S3DeliveryProperties {
    private String bucket;
    private String prefix = "securedoc-output";
    private String region = "us-east-1";
    private String endpoint;
    private boolean pathStyleAccess;

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public boolean isPathStyleAccess() { return pathStyleAccess; }
    public void setPathStyleAccess(boolean pathStyleAccess) { this.pathStyleAccess = pathStyleAccess; }
}
