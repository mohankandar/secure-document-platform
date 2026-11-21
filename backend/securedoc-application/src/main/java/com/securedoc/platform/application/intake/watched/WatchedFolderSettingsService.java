package com.securedoc.platform.application.intake.watched;

import com.securedoc.platform.application.port.out.WatchedFolderSettingsRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.SensitivityLevel;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * The sensitivity level each watched folder gives the files it picks up (task 0021). Tenant IT sets it; the default
 * is Standard. It is a default: a later trigger-file mapping may give a drop its own level instead.
 */
public final class WatchedFolderSettingsService {

    /** The one folder set when LexaVault is installed. Later folders get their own keys. */
    public static final String INSTALLED_FOLDER = "installed";

    private final WatchedFolderSettingsRepository repository;
    private final AuditService auditService;
    private final Clock clock;

    public WatchedFolderSettingsService(WatchedFolderSettingsRepository repository, AuditService auditService, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.auditService = Objects.requireNonNull(auditService);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public SensitivityLevel sensitivityLevel(TenantId tenantId, String folderKey) {
        return repository.findSensitivityLevel(tenantId, folderKey).orElse(SensitivityLevel.STANDARD);
    }

    /** Sets the folder's level. Files already picked up keep the level they were given. */
    public SensitivityLevel changeSensitivityLevel(TenantId tenantId, ActorId actorId, String folderKey,
                                                   SensitivityLevel level, CorrelationId correlationId) {
        Objects.requireNonNull(level, "Sensitivity level is required");
        SensitivityLevel before = sensitivityLevel(tenantId, folderKey);
        Instant now = Instant.now(clock);
        repository.saveSensitivityLevel(tenantId, folderKey, level, actorId, now);
        auditService.recordSuccess(tenantId, actorId, AuditAction.WATCHED_FOLDER_SETTINGS_CHANGED,
                "WatchedFolder", folderKey, correlationId,
                "sensitivityLevel=" + before.name() + "->" + level.name());
        return level;
    }
}
