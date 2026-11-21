package com.securedoc.platform.api.platform;

/** The watched folder's settings (task 0021): the level every file picked up from it gets. */
public record WatchedFolderSettingsResponse(String folderKey, String sensitivityLevel) {
}
