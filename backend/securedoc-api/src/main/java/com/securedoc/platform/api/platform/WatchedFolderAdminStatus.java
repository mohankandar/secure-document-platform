package com.securedoc.platform.api.platform;

import com.securedoc.platform.api.dto.WatchedFolderScanResponse;
import java.time.Instant;

public record WatchedFolderAdminStatus(
        boolean enabled,
        boolean schedulingEnabled,
        long scanIntervalMs,
        String rootPath,
        String triggerFileName,
        int incompleteVisibilityMinutes,
        String acceptedBehavior,
        String rejectedBehavior,
        Instant lastScanAt,
        String lastScanOutcome,
        WatchedFolderScanResponse lastScanResult) {
}
