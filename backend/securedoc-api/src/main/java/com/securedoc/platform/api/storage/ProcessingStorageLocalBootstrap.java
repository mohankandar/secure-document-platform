package com.securedoc.platform.api.storage;

import com.securedoc.platform.application.storage.ProcessingStorageConfigurationService;
import com.securedoc.platform.infrastructure.storage.LocalStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Seeds only the LOCAL developer/demo profile. All other environments remain CONFIGURATION_REQUIRED. */
@Component
public class ProcessingStorageLocalBootstrap implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger log = LoggerFactory.getLogger(ProcessingStorageLocalBootstrap.class);
    private static final UUID SYSTEM_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final Environment environment;
    private final LocalStorageProperties legacyProperties;
    private final ProcessingStorageConfigurationService service;
    private final ProcessingStorageNodeMonitor nodeMonitor;

    public ProcessingStorageLocalBootstrap(Environment environment,
                                           LocalStorageProperties legacyProperties,
                                           ProcessingStorageConfigurationService service,
                                           ProcessingStorageNodeMonitor nodeMonitor) {
        this.environment = environment;
        this.legacyProperties = legacyProperties;
        this.service = service;
        this.nodeMonitor = nodeMonitor;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!environment.acceptsProfiles(Profiles.of("local"))) return;
        try {
            Path root = Path.of(legacyProperties.getRootPath()).toAbsolutePath().normalize();
            Files.createDirectories(root); // LOCAL only; shared/test/prod roots are never created by LexaVault.
            service.bootstrapLocalDefault(root.toString(), SYSTEM_ACTOR);
            nodeMonitor.heartbeatAndValidate();
            log.info("PROCESSING_STORAGE_LOCAL_BOOTSTRAP_READY providerType=LOCAL_FILESYSTEM");
        } catch (Exception e) {
            log.error("PROCESSING_STORAGE_LOCAL_BOOTSTRAP_FAILED reasonCode={}", e.getClass().getSimpleName(), e);
        }
    }
}
