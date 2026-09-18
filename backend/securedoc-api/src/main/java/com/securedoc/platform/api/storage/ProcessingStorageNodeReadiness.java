package com.securedoc.platform.api.storage;

import com.securedoc.platform.application.port.out.ProcessingStorageConfigurationRepository;
import com.securedoc.platform.application.storage.ProcessingStorageConfigurationService;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.storage.ProcessingStorageProviderType;
import com.securedoc.platform.domain.storage.ProcessingStorageValidationStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Tenant-aware processing eligibility for this exact application process. */
@Component
public class ProcessingStorageNodeReadiness {
    private static final Duration FRESHNESS = Duration.ofSeconds(30);

    private final ProcessingStorageConfigurationService service;
    private final ProcessingStorageConfigurationRepository repository;
    private final ProcessingStorageNodeMonitor monitor;

    public ProcessingStorageNodeReadiness(ProcessingStorageConfigurationService service,
                                          ProcessingStorageConfigurationRepository repository,
                                          ProcessingStorageNodeMonitor monitor) {
        this.service = service;
        this.repository = repository;
        this.monitor = monitor;
    }

    public boolean readyFor(TenantId tenantId) {
        var provider = service.effectiveProvider(tenantId).orElse(null);
        if (provider == null) return false;
        Instant cutoff = Instant.now().minus(FRESHNESS);
        long activeNodes = repository.countActiveNodes(cutoff);
        if (activeNodes > 1 && provider.providerType() == ProcessingStorageProviderType.LOCAL_FILESYSTEM) {
            return false;
        }
        return repository.findActiveNodeValidations(provider.id(), cutoff).stream()
                .anyMatch(v -> v.nodeId().equals(monitor.nodeId())
                        && v.processInstanceId().equals(monitor.processInstanceId())
                        && v.status() == ProcessingStorageValidationStatus.HEALTHY);
    }
}
