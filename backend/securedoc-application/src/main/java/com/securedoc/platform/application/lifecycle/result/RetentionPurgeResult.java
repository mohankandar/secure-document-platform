package com.securedoc.platform.application.lifecycle.result;

public record RetentionPurgeResult(int redactedCachesPurged, int packagesFullyPurged) {
}
