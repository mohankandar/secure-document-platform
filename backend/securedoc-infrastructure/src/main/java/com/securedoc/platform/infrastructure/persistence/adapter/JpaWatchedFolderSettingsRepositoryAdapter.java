package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.WatchedFolderSettingsRepository;
import com.securedoc.platform.domain.document.SensitivityLevel;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Table watched_folder_settings (migration 042, task 0021). */
@Component
public class JpaWatchedFolderSettingsRepositoryAdapter implements WatchedFolderSettingsRepository {
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public Optional<SensitivityLevel> findSensitivityLevel(TenantId tenantId, String folderKey) {
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery("""
                select sensitivity_level from watched_folder_settings
                 where tenant_id=:tenant and folder_key=:folder
                """)
                .setParameter("tenant", tenantId.value())
                .setParameter("folder", folderKey)
                .getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(storedLevel(rows.get(0)));
    }

    /**
     * Task 0023 A4: the column has no check constraint, so a value that is not a level (or null) is read as Standard,
     * the same rule as every intake path, instead of failing every drop from the folder.
     */
    static SensitivityLevel storedLevel(Object stored) {
        SensitivityLevel.Received received = SensitivityLevel.fromSource(stored == null ? null : String.valueOf(stored));
        if (received.unknown()) {
            org.slf4j.LoggerFactory.getLogger(JpaWatchedFolderSettingsRepositoryAdapter.class)
                    .warn("WATCHED_FOLDER_SETTING_LEVEL_UNKNOWN reasonCode={} takenAs=STANDARD", SensitivityLevel.UNKNOWN_LEVEL_WARNING);
        }
        return received.level();
    }

    @Override
    @Transactional
    public void saveSensitivityLevel(TenantId tenantId, String folderKey, SensitivityLevel level, ActorId updatedBy,
                                     Instant updatedAt) {
        int updated = entityManager.createNativeQuery("""
                update watched_folder_settings
                   set sensitivity_level=:level, updated_by=:actor, updated_at=:at
                 where tenant_id=:tenant and folder_key=:folder
                """)
                .setParameter("level", level.name())
                .setParameter("actor", updatedBy == null ? null : updatedBy.value())
                .setParameter("at", Timestamp.from(updatedAt))
                .setParameter("tenant", tenantId.value())
                .setParameter("folder", folderKey)
                .executeUpdate();
        if (updated == 0) {
            entityManager.createNativeQuery("""
                    insert into watched_folder_settings (tenant_id, folder_key, sensitivity_level, updated_by, updated_at)
                    values (:tenant, :folder, :level, :actor, :at)
                    """)
                    .setParameter("tenant", tenantId.value())
                    .setParameter("folder", folderKey)
                    .setParameter("level", level.name())
                    .setParameter("actor", updatedBy == null ? null : updatedBy.value())
                    .setParameter("at", Timestamp.from(updatedAt))
                    .executeUpdate();
        }
    }
}
