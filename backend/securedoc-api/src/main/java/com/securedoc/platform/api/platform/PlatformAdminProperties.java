package com.securedoc.platform.api.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Unit 24 safe operational settings for platform-admin background actions. */
@Component
@ConfigurationProperties(prefix = "securedoc.platform-admin")
public class PlatformAdminProperties {
    private boolean watchedFolderSchedulingEnabled = false;
    private long watchedFolderScanIntervalMs = 60000L;
    private String watchedFolderTenantId;
    private String watchedFolderActorId;
    private String watchedFolderActorUsername = "watched-folder-service";

    public boolean isWatchedFolderSchedulingEnabled() { return watchedFolderSchedulingEnabled; }
    public void setWatchedFolderSchedulingEnabled(boolean value) { this.watchedFolderSchedulingEnabled = value; }
    public long getWatchedFolderScanIntervalMs() { return watchedFolderScanIntervalMs; }
    public void setWatchedFolderScanIntervalMs(long value) { this.watchedFolderScanIntervalMs = value; }
    public String getWatchedFolderTenantId() { return watchedFolderTenantId; }
    public void setWatchedFolderTenantId(String value) { this.watchedFolderTenantId = value; }
    public String getWatchedFolderActorId() { return watchedFolderActorId; }
    public void setWatchedFolderActorId(String value) { this.watchedFolderActorId = value; }
    public String getWatchedFolderActorUsername() { return watchedFolderActorUsername; }
    public void setWatchedFolderActorUsername(String value) { this.watchedFolderActorUsername = value; }
}
