package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.domain.document.SensitivityLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Task 0023 A4: whatever the folder setting row holds, a value that is not a level gives Standard. */
class JpaWatchedFolderSettingsStoredLevelTest {

    @Test
    void eachStoredLevelIsKept() {
        for (SensitivityLevel level : SensitivityLevel.values()) {
            assertEquals(level, JpaWatchedFolderSettingsRepositoryAdapter.storedLevel(level.name()));
        }
    }

    @Test
    void anUnknownOrMissingStoredValueIsStandard() {
        assertEquals(SensitivityLevel.STANDARD, JpaWatchedFolderSettingsRepositoryAdapter.storedLevel("TOP_SECRET"));
        assertEquals(SensitivityLevel.STANDARD, JpaWatchedFolderSettingsRepositoryAdapter.storedLevel("confidential"));
        assertEquals(SensitivityLevel.STANDARD, JpaWatchedFolderSettingsRepositoryAdapter.storedLevel(""));
        assertEquals(SensitivityLevel.STANDARD, JpaWatchedFolderSettingsRepositoryAdapter.storedLevel(null));
    }
}
