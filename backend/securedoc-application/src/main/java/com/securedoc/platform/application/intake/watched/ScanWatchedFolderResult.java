package com.securedoc.platform.application.intake.watched;

public record ScanWatchedFolderResult(int discovered, int waitingForTrigger, int accepted, int rejected, int duplicates) {}
