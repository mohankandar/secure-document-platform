package com.securedoc.platform.application.storage;

import com.securedoc.platform.application.port.out.ProcessingStorageConfigurationRepository;
import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.storage.*;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Platform-owned configuration service for LexaVault internal processing storage. */
public final class ProcessingStorageConfigurationService {
    private static final Duration ACTIVE_NODE_WINDOW = Duration.ofSeconds(30);

    private final ProcessingStorageConfigurationRepository repository;
    private final TenantRepository tenantRepository;

    /** Kept for isolated unit tests; production wiring always provides TenantRepository. */
    public ProcessingStorageConfigurationService(ProcessingStorageConfigurationRepository repository) {
        this(repository, null);
    }

    public ProcessingStorageConfigurationService(ProcessingStorageConfigurationRepository repository,
                                                 TenantRepository tenantRepository) {
        this.repository = Objects.requireNonNull(repository);
        this.tenantRepository = tenantRepository;
    }

    public ProcessingStorageProvider createProvider(String name, ProcessingStorageProviderType type,
                                                    String rootPath, UUID actorId) {
        ProcessingStorageProvider provider = ProcessingStorageProvider.create(
                name, type, normalizeRoot(rootPath), repository.nextProviderConfigurationVersion(), actorId, Instant.now());
        repository.saveProvider(provider);
        return provider;
    }

    public ProcessingStorageProvider getProvider(UUID providerId) {
        return requireProvider(providerId);
    }

    public ProcessingStorageOverview overview() {
        List<ProcessingStorageProvider> providers = repository.findAllProviders();
        ProcessingStorageAssignment defaultAssignment = repository.findDeploymentDefault().orElse(null);
        List<ProcessingStorageAssignment> overrides = repository.findTenantOverrides();
        Instant cutoff = Instant.now().minus(ACTIVE_NODE_WINDOW);
        LinkedHashSet<UUID> assignedProviders = new LinkedHashSet<>();
        if (defaultAssignment != null) assignedProviders.add(defaultAssignment.providerId());
        overrides.forEach(a -> assignedProviders.add(a.providerId()));

        // Return validation state for every non-retired provider, including CANDIDATE providers.
        // This makes Test Connection results visible before activation while readiness still depends
        // only on providers that are actually assigned.
        List<ProcessingStorageNodeValidation> validations = providers.stream()
                .filter(p -> p.status() != ProcessingStorageProviderStatus.RETIRED)
                .flatMap(p -> repository.findActiveNodeValidations(p.id(), cutoff).stream())
                .toList();
        long activeNodeCount = repository.countActiveNodes(cutoff);
        Map<UUID, ProcessingStorageProvider> providersById = providers.stream()
                .collect(java.util.stream.Collectors.toMap(ProcessingStorageProvider::id, p -> p));
        boolean ready = defaultAssignment != null && assignedProviders.stream()
                .allMatch(id -> topologyAllows(providersById.get(id), activeNodeCount)
                        && allActiveNodesHealthy(id, cutoff));
        List<ProcessingStorageTenantSummary> tenants = tenantRepository == null ? List.of()
                : tenantRepository.findAll().stream()
                    .map(t -> new ProcessingStorageTenantSummary(
                            t.id().value(), t.name(), t.slug(), t.status().name()))
                    .sorted(Comparator.comparing(ProcessingStorageTenantSummary::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        return new ProcessingStorageOverview(defaultAssignment, overrides, providers, validations, tenants, activeNodeCount, ready);
    }

    public ProcessingStorageProvider activateDeploymentDefault(UUID providerId, UUID actorId) {
        ProcessingStorageProvider provider = requireProvider(providerId);
        ensureActivatable(provider);
        ensureAllActiveNodesHealthy(providerId);
        ProcessingStorageProvider available = provider.markAvailable(Instant.now());
        repository.saveProvider(available);
        repository.activateDeploymentDefault(providerId, actorId, Instant.now());
        repository.claimLegacyLocalArtifacts(providerId, normalizeRoot(provider.rootPath()));
        return available;
    }

    public ProcessingStorageProvider activateTenantOverride(TenantId tenantId, UUID providerId, UUID actorId) {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        ProcessingStorageProvider provider = requireProvider(providerId);
        ensureActivatable(provider);
        ensureAllActiveNodesHealthy(providerId);
        ProcessingStorageProvider available = provider.markAvailable(Instant.now());
        repository.saveProvider(available);
        repository.activateTenantOverride(tenantId, providerId, actorId, Instant.now());
        return available;
    }

    public void clearTenantOverride(TenantId tenantId) {
        repository.clearTenantOverride(Objects.requireNonNull(tenantId));
    }

    public ProcessingStorageProvider retire(UUID providerId) {
        ProcessingStorageProvider provider = requireProvider(providerId);
        if (repository.findDeploymentDefault().map(a -> a.providerId().equals(providerId)).orElse(false)
                || repository.findTenantOverrides().stream().anyMatch(a -> a.providerId().equals(providerId))) {
            throw new ValidationException("Active processing storage provider cannot be retired");
        }
        ProcessingStorageProvider retired = provider.retire(Instant.now());
        repository.saveProvider(retired);
        return retired;
    }

    /** LOCAL-only bootstrap path. Deliberately bypasses cluster activation because it seeds one developer deployment. */
    public ProcessingStorageProvider bootstrapLocalDefault(String rootPath, UUID actorId) {
        Optional<ProcessingStorageAssignment> existing = repository.findDeploymentDefault();
        if (existing.isPresent()) return requireProvider(existing.get().providerId());
        String normalized = normalizeRoot(rootPath);
        ProcessingStorageProvider existingProvider = repository.findAllProviders().stream()
                .filter(p -> p.providerType() == ProcessingStorageProviderType.LOCAL_FILESYSTEM)
                .filter(p -> normalizeRoot(p.rootPath()).equals(normalized))
                .findFirst().orElse(null);
        ProcessingStorageProvider provider = existingProvider == null
                ? ProcessingStorageProvider.create("Local Processing Storage",
                    ProcessingStorageProviderType.LOCAL_FILESYSTEM, normalized,
                    repository.nextProviderConfigurationVersion(), actorId, Instant.now())
                : existingProvider;
        provider = provider.markAvailable(Instant.now());
        repository.saveProvider(provider);
        repository.activateDeploymentDefault(provider.id(), actorId, Instant.now());
        repository.claimLegacyLocalArtifacts(provider.id(), normalized);
        return provider;
    }

    public Optional<ProcessingStorageProvider> effectiveProvider(TenantId tenantId) {
        Optional<ProcessingStorageAssignment> assignment = repository.findTenantOverride(tenantId)
                .or(repository::findDeploymentDefault);
        return assignment.flatMap(a -> repository.findProvider(a.providerId()))
                .filter(p -> p.status() != ProcessingStorageProviderStatus.RETIRED);
    }

    private ProcessingStorageProvider requireProvider(UUID providerId) {
        Objects.requireNonNull(providerId, "Processing storage provider id is required");
        return repository.findProvider(providerId)
                .orElseThrow(() -> new NotFoundException("ProcessingStorageProvider", providerId.toString()));
    }

    private void ensureActivatable(ProcessingStorageProvider provider) {
        if (provider.status() == ProcessingStorageProviderStatus.RETIRED) {
            throw new ValidationException("Retired processing storage provider cannot be activated");
        }
    }

    private void ensureAllActiveNodesHealthy(UUID providerId) {
        Instant cutoff = Instant.now().minus(ACTIVE_NODE_WINDOW);
        ProcessingStorageProvider provider = requireProvider(providerId);
        long activeNodes = repository.countActiveNodes(cutoff);
        if (provider.providerType() == ProcessingStorageProviderType.LOCAL_FILESYSTEM && activeNodes > 1) {
            throw new ValidationException("LOCAL_FILESYSTEM cannot be activated while multiple application nodes are active; use SHARED_FILESYSTEM");
        }
        if (!allActiveNodesHealthy(providerId, cutoff)) {
            throw new ValidationException("Processing storage has not validated healthy on every active application node");
        }
    }

    private boolean topologyAllows(ProcessingStorageProvider provider, long activeNodeCount) {
        if (provider == null || provider.status() == ProcessingStorageProviderStatus.RETIRED) return false;
        return activeNodeCount <= 1 || provider.providerType() != ProcessingStorageProviderType.LOCAL_FILESYSTEM;
    }

    private boolean allActiveNodesHealthy(UUID providerId, Instant cutoff) {
        long activeNodes = repository.countActiveNodes(cutoff);
        if (activeNodes < 1) return false;
        long healthyNodes = repository.findActiveNodeValidations(providerId, cutoff).stream()
                .filter(v -> v.status() == ProcessingStorageValidationStatus.HEALTHY)
                .map(v -> v.nodeId() + ":" + v.processInstanceId())
                .distinct().count();
        return healthyNodes == activeNodes;
    }

    public static String normalizeRoot(String rootPath) {
        if (rootPath == null || rootPath.isBlank()) throw new IllegalArgumentException("Processing storage root path is required");
        return Path.of(rootPath.trim()).toAbsolutePath().normalize().toString();
    }
}
