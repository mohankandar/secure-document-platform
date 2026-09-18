package com.securedoc.platform.application.storage;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.ProcessingStorageConfigurationRepository;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.storage.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProcessingStorageConfigurationServiceTest {

    @Test
    void activationRequiresEveryActiveNodeToValidateHealthy() {
        FakeRepository repository = new FakeRepository();
        ProcessingStorageConfigurationService service = new ProcessingStorageConfigurationService(repository);
        UUID actor = UUID.randomUUID();
        ProcessingStorageProvider provider = service.createProvider(
                "Shared", ProcessingStorageProviderType.SHARED_FILESYSTEM, tempPath("shared"), actor);

        repository.addActiveNode("app-01", true, provider.id());
        repository.addActiveNode("app-02", false, provider.id());

        ValidationException error = assertThrows(ValidationException.class,
                () -> service.activateDeploymentDefault(provider.id(), actor));
        assertTrue(error.getMessage().contains("every active application node"));
        assertTrue(repository.findDeploymentDefault().isEmpty());

        repository.setValidation("app-02", provider.id(), ProcessingStorageValidationStatus.HEALTHY);
        ProcessingStorageProvider activated = service.activateDeploymentDefault(provider.id(), actor);

        assertEquals(ProcessingStorageProviderStatus.AVAILABLE, activated.status());
        assertEquals(provider.id(), repository.findDeploymentDefault().orElseThrow().providerId());
    }

    @Test
    void localFilesystemCannotBeActivatedWithMultipleActiveNodes() {
        FakeRepository repository = new FakeRepository();
        ProcessingStorageConfigurationService service = new ProcessingStorageConfigurationService(repository);
        UUID actor = UUID.randomUUID();
        ProcessingStorageProvider provider = service.createProvider(
                "Local", ProcessingStorageProviderType.LOCAL_FILESYSTEM, tempPath("local"), actor);

        repository.addActiveNode("app-01", true, provider.id());
        repository.addActiveNode("app-02", true, provider.id());

        ValidationException error = assertThrows(ValidationException.class,
                () -> service.activateDeploymentDefault(provider.id(), actor));
        assertTrue(error.getMessage().contains("LOCAL_FILESYSTEM"));
    }

    @Test
    void alreadyActiveLocalDefaultBecomesNotReadyWhenSecondNodeJoins() {
        FakeRepository repository = new FakeRepository();
        ProcessingStorageConfigurationService service = new ProcessingStorageConfigurationService(repository);
        UUID actor = UUID.randomUUID();
        ProcessingStorageProvider provider = service.createProvider(
                "Local", ProcessingStorageProviderType.LOCAL_FILESYSTEM, tempPath("local-existing"), actor);

        repository.addActiveNode("app-01", true, provider.id());
        service.activateDeploymentDefault(provider.id(), actor);
        assertTrue(service.overview().ready());

        repository.addActiveNode("app-02", true, provider.id());
        ProcessingStorageOverview overview = service.overview();

        assertEquals(2, overview.activeNodeCount());
        assertFalse(overview.ready());
        assertEquals(provider.id(), overview.deploymentDefault().providerId());
    }

    @Test
    void alreadyAssignedLocalTenantOverrideMakesClusterNotReadyWhenSecondNodeJoins() {
        FakeRepository repository = new FakeRepository();
        ProcessingStorageConfigurationService service = new ProcessingStorageConfigurationService(repository);
        UUID actor = UUID.randomUUID();
        TenantId tenant = TenantId.generate();
        ProcessingStorageProvider shared = service.createProvider(
                "Shared", ProcessingStorageProviderType.SHARED_FILESYSTEM, tempPath("shared-default"), actor);
        ProcessingStorageProvider local = service.createProvider(
                "Local Override", ProcessingStorageProviderType.LOCAL_FILESYSTEM, tempPath("local-override"), actor);

        repository.addActiveNode("app-01", true, shared.id());
        repository.setValidation("app-01", local.id(), ProcessingStorageValidationStatus.HEALTHY);
        service.activateDeploymentDefault(shared.id(), actor);
        service.activateTenantOverride(tenant, local.id(), actor);
        assertTrue(service.overview().ready());

        repository.addActiveNode("app-02", true, shared.id());
        repository.setValidation("app-02", local.id(), ProcessingStorageValidationStatus.HEALTHY);
        ProcessingStorageOverview overview = service.overview();

        assertEquals(2, overview.activeNodeCount());
        assertFalse(overview.ready());
    }

    @Test
    void retiredProviderMayRemainReferencedButCannotRemainAssigned() {
        FakeRepository repository = new FakeRepository();
        ProcessingStorageConfigurationService service = new ProcessingStorageConfigurationService(repository);
        UUID actor = UUID.randomUUID();

        ProcessingStorageProvider oldProvider = service.createProvider(
                "Old", ProcessingStorageProviderType.SHARED_FILESYSTEM, tempPath("old"), actor);
        ProcessingStorageProvider newProvider = service.createProvider(
                "New", ProcessingStorageProviderType.SHARED_FILESYSTEM, tempPath("new"), actor);
        repository.addActiveNode("app-01", true, oldProvider.id());
        repository.setValidation("app-01", newProvider.id(), ProcessingStorageValidationStatus.HEALTHY);

        service.activateDeploymentDefault(oldProvider.id(), actor);
        assertThrows(ValidationException.class, () -> service.retire(oldProvider.id()));

        service.activateDeploymentDefault(newProvider.id(), actor);
        repository.providersWithReferences.add(oldProvider.id());
        ProcessingStorageProvider retired = service.retire(oldProvider.id());

        assertEquals(ProcessingStorageProviderStatus.RETIRED, retired.status());
        assertTrue(repository.providerHasArtifactReferences(oldProvider.id()));
        assertEquals(newProvider.id(), repository.findDeploymentDefault().orElseThrow().providerId());
    }

    @Test
    void overviewIncludesCandidateValidationBeforeActivation() {
        FakeRepository repository = new FakeRepository();
        ProcessingStorageConfigurationService service = new ProcessingStorageConfigurationService(repository);
        UUID actor = UUID.randomUUID();
        ProcessingStorageProvider provider = service.createProvider(
                "Candidate", ProcessingStorageProviderType.SHARED_FILESYSTEM, tempPath("candidate"), actor);
        repository.addActiveNode("app-01", true, provider.id());

        ProcessingStorageOverview overview = service.overview();

        assertFalse(overview.ready());
        assertEquals(1, overview.activeNodeCount());
        assertEquals(1, overview.nodeValidations().size());
        assertEquals(provider.id(), overview.nodeValidations().get(0).providerId());
        assertEquals(ProcessingStorageValidationStatus.HEALTHY, overview.nodeValidations().get(0).status());
    }

    @Test
    void tenantOverrideWinsOverDeploymentDefault() {
        FakeRepository repository = new FakeRepository();
        ProcessingStorageConfigurationService service = new ProcessingStorageConfigurationService(repository);
        UUID actor = UUID.randomUUID();
        TenantId tenant = TenantId.generate();
        ProcessingStorageProvider defaultProvider = service.createProvider(
                "Default", ProcessingStorageProviderType.SHARED_FILESYSTEM, tempPath("default"), actor);
        ProcessingStorageProvider tenantProvider = service.createProvider(
                "Title", ProcessingStorageProviderType.SHARED_FILESYSTEM, tempPath("title"), actor);
        repository.addActiveNode("app-01", true, defaultProvider.id());
        repository.setValidation("app-01", tenantProvider.id(), ProcessingStorageValidationStatus.HEALTHY);

        service.activateDeploymentDefault(defaultProvider.id(), actor);
        service.activateTenantOverride(tenant, tenantProvider.id(), actor);

        assertEquals(tenantProvider.id(), service.effectiveProvider(tenant).orElseThrow().id());
        service.clearTenantOverride(tenant);
        assertEquals(defaultProvider.id(), service.effectiveProvider(tenant).orElseThrow().id());
    }

    private static String tempPath(String suffix) {
        return System.getProperty("java.io.tmpdir") + "/lexavault-storage-test-" + suffix + "-" + UUID.randomUUID();
    }

    private static final class FakeRepository implements ProcessingStorageConfigurationRepository {
        private final Map<UUID, ProcessingStorageProvider> providers = new LinkedHashMap<>();
        private final Map<TenantId, ProcessingStorageAssignment> tenantAssignments = new HashMap<>();
        private final Map<String, NodeState> nodes = new LinkedHashMap<>();
        private final Set<UUID> providersWithReferences = new HashSet<>();
        private ProcessingStorageAssignment deploymentDefault;
        private int nextVersion = 1;

        @Override public List<ProcessingStorageProvider> findAllProviders() { return new ArrayList<>(providers.values()); }
        @Override public Optional<ProcessingStorageProvider> findProvider(UUID providerId) { return Optional.ofNullable(providers.get(providerId)); }
        @Override public void saveProvider(ProcessingStorageProvider provider) { providers.put(provider.id(), provider); }
        @Override public int nextProviderConfigurationVersion() { return nextVersion++; }
        @Override public Optional<ProcessingStorageAssignment> findDeploymentDefault() { return Optional.ofNullable(deploymentDefault); }
        @Override public Optional<ProcessingStorageAssignment> findTenantOverride(TenantId tenantId) { return Optional.ofNullable(tenantAssignments.get(tenantId)); }
        @Override public List<ProcessingStorageAssignment> findTenantOverrides() { return new ArrayList<>(tenantAssignments.values()); }
        @Override public void activateDeploymentDefault(UUID providerId, UUID actorId, Instant now) {
            deploymentDefault = new ProcessingStorageAssignment(UUID.randomUUID(), null, providerId, actorId, now);
        }
        @Override public void activateTenantOverride(TenantId tenantId, UUID providerId, UUID actorId, Instant now) {
            tenantAssignments.put(tenantId, new ProcessingStorageAssignment(UUID.randomUUID(), tenantId, providerId, actorId, now));
        }
        @Override public void clearTenantOverride(TenantId tenantId) { tenantAssignments.remove(tenantId); }
        @Override public int nextProcessingCycle(TenantId tenantId, PackageId packageId) { return 1; }
        @Override public Optional<UUID> inferCycleOneProviderFromExistingArtifacts(TenantId tenantId, PackageId packageId) { return Optional.empty(); }
        @Override public Optional<UUID> findPinnedProvider(TenantId tenantId, PackageId packageId, int processingCycle) { return Optional.empty(); }
        @Override public UUID pinProvider(TenantId tenantId, PackageId packageId, int processingCycle, UUID providerId, Instant now) { return providerId; }
        @Override public void heartbeatNode(String nodeId, UUID processInstanceId, Instant now) { }
        @Override public void saveNodeValidation(ProcessingStorageNodeValidation validation) {
            nodes.computeIfAbsent(validation.nodeId(), k -> new NodeState(validation.processInstanceId()))
                    .validations.put(validation.providerId(), validation.status());
        }
        @Override public List<ProcessingStorageNodeValidation> findActiveNodeValidations(UUID providerId, Instant heartbeatCutoff) {
            Instant now = Instant.now();
            List<ProcessingStorageNodeValidation> result = new ArrayList<>();
            for (Map.Entry<String, NodeState> entry : nodes.entrySet()) {
                NodeState state = entry.getValue();
                ProcessingStorageValidationStatus status = state.validations.get(providerId);
                if (state.active && status != null) {
                    result.add(new ProcessingStorageNodeValidation(entry.getKey(), state.processInstanceId, providerId, status,
                            status == ProcessingStorageValidationStatus.HEALTHY ? null : "TEST_FAILURE", now));
                }
            }
            return result;
        }
        @Override public long countActiveNodes(Instant heartbeatCutoff) { return nodes.values().stream().filter(n -> n.active).count(); }
        @Override public boolean providerHasArtifactReferences(UUID providerId) { return providersWithReferences.contains(providerId); }
        @Override public int claimLegacyLocalArtifacts(UUID providerId, String absoluteRootPath) { return 0; }

        void addActiveNode(String nodeId, boolean healthy, UUID providerId) {
            NodeState state = nodes.computeIfAbsent(nodeId, k -> new NodeState(UUID.randomUUID()));
            state.active = true;
            state.validations.put(providerId, healthy ? ProcessingStorageValidationStatus.HEALTHY : ProcessingStorageValidationStatus.UNHEALTHY);
        }

        void setValidation(String nodeId, UUID providerId, ProcessingStorageValidationStatus status) {
            NodeState state = nodes.computeIfAbsent(nodeId, k -> new NodeState(UUID.randomUUID()));
            state.active = true;
            state.validations.put(providerId, status);
        }

        private static final class NodeState {
            private final UUID processInstanceId;
            private final Map<UUID, ProcessingStorageValidationStatus> validations = new HashMap<>();
            private boolean active = true;
            private NodeState(UUID processInstanceId) { this.processInstanceId = processInstanceId; }
        }
    }
}
