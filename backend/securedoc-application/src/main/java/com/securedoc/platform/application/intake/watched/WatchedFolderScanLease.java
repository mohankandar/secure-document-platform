package com.securedoc.platform.application.intake.watched;

/**
 * Fencing context carried by one watched-folder scan.
 * A positive fencing token represents a distributed lease generation; token zero is the explicit single-node mode.
 */
public record WatchedFolderScanLease(String leaseName, String ownerId, long fencingToken) {
    public WatchedFolderScanLease {
        if (leaseName == null || leaseName.isBlank()) throw new IllegalArgumentException("leaseName is required");
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId is required");
        if (fencingToken < 0) throw new IllegalArgumentException("fencingToken must be non-negative");
    }

    public static WatchedFolderScanLease singleNode() {
        return new WatchedFolderScanLease("single-node", "single-node", 0L);
    }
}
