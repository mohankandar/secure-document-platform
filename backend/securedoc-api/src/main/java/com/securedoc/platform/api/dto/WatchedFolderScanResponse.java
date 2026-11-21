package com.securedoc.platform.api.dto;

/**
 * Counts of one watched-folder scan. {@code outcome} is {@code SCANNED} when the folder was scanned, otherwise why it
 * was not (task 0023 #340): {@code STANDBY} (another node holds the scan lease), {@code NOT_CONFIGURED} or
 * {@code COORDINATION_DEGRADED}, so all-zero counts are not mistaken for an empty folder.
 */
public record WatchedFolderScanResponse(int discovered, int waitingForTrigger, int accepted, int rejected, int duplicates,
                                        String outcome) {
    public static final String SCANNED = "SCANNED";
}
