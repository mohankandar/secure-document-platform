package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.storage.ProcessingStorageAssignment;
import com.securedoc.platform.domain.storage.ProcessingStorageNodeValidation;
import com.securedoc.platform.domain.storage.ProcessingStorageProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for deployment/tenant processing-storage configuration and HA validation state. */
public interface ProcessingStorageConfigurationRepository {
    List<ProcessingStorageProvider> findAllProviders();
    Optional<ProcessingStorageProvider> findProvider(UUID providerId);
    void saveProvider(ProcessingStorageProvider provider);
    int nextProviderConfigurationVersion();

    Optional<ProcessingStorageAssignment> findDeploymentDefault();
    Optional<ProcessingStorageAssignment> findTenantOverride(TenantId tenantId);
    List<ProcessingStorageAssignment> findTenantOverrides();
    void activateDeploymentDefault(UUID providerId, UUID actorId, Instant now);
    void activateTenantOverride(TenantId tenantId, UUID providerId, UUID actorId, Instant now);
    void clearTenantOverride(TenantId tenantId);

    int nextProcessingCycle(TenantId tenantId, PackageId packageId);
    Optional<UUID> inferCycleOneProviderFromExistingArtifacts(TenantId tenantId, PackageId packageId);
    Optional<UUID> findPinnedProvider(TenantId tenantId, PackageId packageId, int processingCycle);
    UUID pinProvider(TenantId tenantId, PackageId packageId, int processingCycle, UUID providerId, Instant now);

    void heartbeatNode(String nodeId, UUID processInstanceId, Instant now);
    void saveNodeValidation(ProcessingStorageNodeValidation validation);
    List<ProcessingStorageNodeValidation> findActiveNodeValidations(UUID providerId, Instant heartbeatCutoff);
    long countActiveNodes(Instant heartbeatCutoff);

    boolean providerHasArtifactReferences(UUID providerId);
    int claimLegacyLocalArtifacts(UUID providerId, String absoluteRootPath);
}
