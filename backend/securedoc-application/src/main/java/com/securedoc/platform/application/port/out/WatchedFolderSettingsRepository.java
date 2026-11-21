package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.document.SensitivityLevel;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Optional;

/** Per-folder settings set by Tenant IT (task 0021: the folder's sensitivity level). Table watched_folder_settings. */
public interface WatchedFolderSettingsRepository {

    /** The folder's level, or empty when Tenant IT never set one (the default is Standard). */
    Optional<SensitivityLevel> findSensitivityLevel(TenantId tenantId, String folderKey);

    void saveSensitivityLevel(TenantId tenantId, String folderKey, SensitivityLevel level, ActorId updatedBy,
                              Instant updatedAt);
}
