package com.securedoc.platform.infrastructure.storage;

import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.port.out.ProcessingStorageConfigurationRepository;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.storage.ProcessingStorageAssignment;
import com.securedoc.platform.domain.storage.ProcessingStorageNodeValidation;
import com.securedoc.platform.domain.storage.ProcessingStorageProvider;
import com.securedoc.platform.domain.storage.ProcessingStorageProviderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalFileStorageAdapterTopologyTest {

    @TempDir
    Path tempDir;

    @Test
    void localFilesystemWriteFailsClosedWhenMultipleNodesAreActive() {
        TenantId tenantId = TenantId.generate();
        ProcessingStorageProvider provider = availableProvider(ProcessingStorageProviderType.LOCAL_FILESYSTEM, tempDir);
        StubRepository repository = new StubRepository(provider, 2);
        LocalFileStorageAdapter adapter = adapter(repository);

        TechnicalDependencyException error = assertThrows(TechnicalDependencyException.class,
                () -> adapter.store(tenantId, "tenant/package/document/ORIGINAL/artifact",
                        new ByteArrayInputStream("pdf".getBytes()), 3, "application/pdf"));

        assertTrue(error.getMessage().contains("LOCAL_FILESYSTEM"));
        assertFalse(Files.exists(tempDir.resolve("tenant/package/document/ORIGINAL/artifact")));
    }

    @Test
    void sharedFilesystemWriteRemainsAllowedWhenMultipleNodesAreActive() {
        TenantId tenantId = TenantId.generate();
        ProcessingStorageProvider provider = availableProvider(ProcessingStorageProviderType.SHARED_FILESYSTEM, tempDir);
        StubRepository repository = new StubRepository(provider, 3);
        LocalFileStorageAdapter adapter = adapter(repository);

        var reference = adapter.store(tenantId, "tenant/package/document/ORIGINAL/artifact",
                new ByteArrayInputStream("pdf".getBytes()), 3, "application/pdf");

        assertEquals(provider.id().toString(), reference.storageProvider());
        assertEquals(ProcessingStorageProviderType.SHARED_FILESYSTEM.name(), reference.bucket());
        assertTrue(Files.exists(tempDir.resolve("tenant/package/document/ORIGINAL/artifact")));
    }

    private LocalFileStorageAdapter adapter(ProcessingStorageConfigurationRepository repository) {
        LocalStorageProperties legacy = new LocalStorageProperties();
        legacy.setRootPath(tempDir.toString());
        return new LocalFileStorageAdapter(legacy, repository);
    }

    private ProcessingStorageProvider availableProvider(ProcessingStorageProviderType type, Path root) {
        Instant now = Instant.now();
        return ProcessingStorageProvider.create("Test", type, root.toString(), 1, UUID.randomUUID(), now)
                .markAvailable(now);
    }

    private static final class StubRepository implements ProcessingStorageConfigurationRepository {
        private final ProcessingStorageProvider provider;
        private final ProcessingStorageAssignment assignment;
        private final long activeNodes;

        private StubRepository(ProcessingStorageProvider provider, long activeNodes) {
            this.provider = provider;
            this.assignment = new ProcessingStorageAssignment(UUID.randomUUID(), null, provider.id(), UUID.randomUUID(), Instant.now());
            this.activeNodes = activeNodes;
        }

        @Override public List<ProcessingStorageProvider> findAllProviders() { return List.of(provider); }
        @Override public Optional<ProcessingStorageProvider> findProvider(UUID providerId) { return provider.id().equals(providerId) ? Optional.of(provider) : Optional.empty(); }
        @Override public void saveProvider(ProcessingStorageProvider provider) { throw new UnsupportedOperationException(); }
        @Override public int nextProviderConfigurationVersion() { return 1; }
        @Override public Optional<ProcessingStorageAssignment> findDeploymentDefault() { return Optional.of(assignment); }
        @Override public Optional<ProcessingStorageAssignment> findTenantOverride(TenantId tenantId) { return Optional.empty(); }
        @Override public List<ProcessingStorageAssignment> findTenantOverrides() { return List.of(); }
        @Override public void activateDeploymentDefault(UUID providerId, UUID actorId, Instant now) { throw new UnsupportedOperationException(); }
        @Override public void activateTenantOverride(TenantId tenantId, UUID providerId, UUID actorId, Instant now) { throw new UnsupportedOperationException(); }
        @Override public void clearTenantOverride(TenantId tenantId) { throw new UnsupportedOperationException(); }
        @Override public int nextProcessingCycle(TenantId tenantId, PackageId packageId) { return 1; }
        @Override public Optional<UUID> inferCycleOneProviderFromExistingArtifacts(TenantId tenantId, PackageId packageId) { return Optional.empty(); }
        @Override public Optional<UUID> findPinnedProvider(TenantId tenantId, PackageId packageId, int processingCycle) { return Optional.empty(); }
        @Override public UUID pinProvider(TenantId tenantId, PackageId packageId, int processingCycle, UUID providerId, Instant now) { return providerId; }
        @Override public void heartbeatNode(String nodeId, UUID processInstanceId, Instant now) { throw new UnsupportedOperationException(); }
        @Override public void saveNodeValidation(ProcessingStorageNodeValidation validation) { throw new UnsupportedOperationException(); }
        @Override public List<ProcessingStorageNodeValidation> findActiveNodeValidations(UUID providerId, Instant heartbeatCutoff) { return List.of(); }
        @Override public long countActiveNodes(Instant heartbeatCutoff) { return activeNodes; }
        @Override public boolean providerHasArtifactReferences(UUID providerId) { return false; }
        @Override public int claimLegacyLocalArtifacts(UUID providerId, String absoluteRootPath) { return 0; }
    }
}
