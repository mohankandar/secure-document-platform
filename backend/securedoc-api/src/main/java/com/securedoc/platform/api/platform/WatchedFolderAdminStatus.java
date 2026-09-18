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
        String processingDirectory,
        boolean coordinationEnabled,
        String nodeId,
        String nodeInstanceId,
        String scannerRole,
        String leaseOwner,
        Long fencingToken,
        Instant leaseAcquiredAt,
        Instant leaseHeartbeatAt,
        Instant leaseExpiresAt,
        long takeoverCount,
        Instant lastScanAt,
        String lastScanOutcome,
        WatchedFolderScanResponse lastScanResult) {
}
