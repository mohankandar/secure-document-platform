package com.securedoc.platform.api.storage;

import com.securedoc.platform.application.port.out.ProcessingStorageConfigurationRepository;
import com.securedoc.platform.domain.storage.ProcessingStorageNodeValidation;
import com.securedoc.platform.domain.storage.ProcessingStorageProviderStatus;
import com.securedoc.platform.infrastructure.storage.ProcessingStorageFilesystemProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Heartbeats each application process and continuously verifies candidate/available processing-storage roots. */
@Component
public class ProcessingStorageNodeMonitor {
    private static final Logger log = LoggerFactory.getLogger(ProcessingStorageNodeMonitor.class);

    private final ProcessingStorageConfigurationRepository repository;
    private final ProcessingStorageFilesystemProbe probe;
    private final String nodeId;
    private final UUID processInstanceId = UUID.randomUUID();

    public ProcessingStorageNodeMonitor(ProcessingStorageConfigurationRepository repository,
                                        ProcessingStorageFilesystemProbe probe,
                                        @Value("${securedoc.logging.node-id:unknown}") String nodeId) {
        this.repository = repository;
        this.probe = probe;
        this.nodeId = nodeId == null || nodeId.isBlank() ? "unknown" : nodeId.trim();
    }

    @Scheduled(fixedDelayString = "${securedoc.storage.managed.validation-interval-ms:5000}")
    public void heartbeatAndValidate() {
        Instant now = Instant.now();
        try {
            repository.heartbeatNode(nodeId, processInstanceId, now);
            repository.findAllProviders().stream()
                    .filter(provider -> provider.status() != ProcessingStorageProviderStatus.RETIRED)
                    .forEach(provider -> {
                        var result = probe.validate(provider, nodeId, processInstanceId);
                        repository.saveNodeValidation(new ProcessingStorageNodeValidation(
                                nodeId, processInstanceId, provider.id(), result.status(), result.failureCode(), result.checkedAt()));
                    });
        } catch (Exception e) {
            log.warn("PROCESSING_STORAGE_NODE_MONITOR_FAILED nodeId={} reasonCode={}", nodeId, e.getClass().getSimpleName(), e);
        }
    }

    public String nodeId() { return nodeId; }
    public UUID processInstanceId() { return processInstanceId; }
}
