package com.securedoc.platform.api.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Safe operational settings for platform-admin background actions and multi-node watched intake. */
@Component
@ConfigurationProperties(prefix = "securedoc.platform-admin")
public class PlatformAdminProperties {
    private boolean watchedFolderSchedulingEnabled = false;
    private long watchedFolderScanIntervalMs = 60000L;
    private String watchedFolderTenantId;
    private String watchedFolderActorId;
    private String watchedFolderActorUsername = "watched-folder-service";
    private boolean watchedFolderCoordinationEnabled = false;
    private String watchedFolderNodeId;
    private String watchedFolderLeaseName = "watched-folder-intake";
    private long watchedFolderLeaseDurationMs = 30000L;

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
    public boolean isWatchedFolderCoordinationEnabled() { return watchedFolderCoordinationEnabled; }
    public void setWatchedFolderCoordinationEnabled(boolean value) { this.watchedFolderCoordinationEnabled = value; }
    public String getWatchedFolderNodeId() { return watchedFolderNodeId; }
    public void setWatchedFolderNodeId(String value) { this.watchedFolderNodeId = value; }
    public String getWatchedFolderLeaseName() { return watchedFolderLeaseName; }
    public void setWatchedFolderLeaseName(String value) { this.watchedFolderLeaseName = value; }
    public long getWatchedFolderLeaseDurationMs() { return watchedFolderLeaseDurationMs; }
    public void setWatchedFolderLeaseDurationMs(long value) { this.watchedFolderLeaseDurationMs = value; }
}
