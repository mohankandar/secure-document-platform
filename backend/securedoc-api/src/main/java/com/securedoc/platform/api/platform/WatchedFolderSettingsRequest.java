package com.securedoc.platform.api.platform;

/** Tenant IT's change to the watched folder's level: STANDARD, CONFIDENTIAL or HIGHLY_CONFIDENTIAL (exact names). */
public record WatchedFolderSettingsRequest(String sensitivityLevel) {
}
