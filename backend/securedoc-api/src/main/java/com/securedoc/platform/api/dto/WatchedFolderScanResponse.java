package com.securedoc.platform.api.dto;
public record WatchedFolderScanResponse(int discovered, int waitingForTrigger, int accepted, int rejected, int duplicates) {}
