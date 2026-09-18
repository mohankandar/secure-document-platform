package com.securedoc.platform.infrastructure.storage;

import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.ProcessingStorageConfigurationRepository;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.storage.ProcessingStorageAssignment;
import com.securedoc.platform.domain.storage.ProcessingStorageProvider;
import com.securedoc.platform.domain.storage.ProcessingStorageProviderStatus;
import com.securedoc.platform.domain.storage.ProcessingStorageProviderType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Managed filesystem implementation of {@link StoragePort}.
 *
 * <p>The historical class name is retained to avoid a broad wiring change, but new writes are no
 * longer bound to {@code securedoc.storage.local.root-path}. They resolve a PostgreSQL-backed
 * deployment/tenant provider and pin it per package processing cycle. The old local setting remains
 * read/delete compatible for pre-028 artifact references.</p>
 */
@Component
public class LocalFileStorageAdapter implements StoragePort {

    private static final Duration ACTIVE_NODE_WINDOW = Duration.ofSeconds(30);

    private final LocalStorageProperties legacyProperties;
    private final ProcessingStorageConfigurationRepository configuration;

    public LocalFileStorageAdapter(LocalStorageProperties legacyProperties,
                                   ProcessingStorageConfigurationRepository configuration) {
        this.legacyProperties = Objects.requireNonNull(legacyProperties);
        this.configuration = Objects.requireNonNull(configuration);
    }

    @Override
    public ArtifactStorageReference store(TenantId tenantId, String path,
                                          InputStream content, long sizeBytes, String mimeType) {
        ProcessingStorageProvider provider = effectiveProvider(tenantId);
        return storeOn(provider, path, content);
    }

    @Override
    public ArtifactStorageReference storeForPackage(TenantId tenantId, PackageId packageId,
                                                      String path, InputStream content,
                                                      long sizeBytes, String mimeType) {
        int processingCycle = configuration.nextProcessingCycle(tenantId, packageId);
        return storeForProcessingCycle(tenantId, packageId, processingCycle, path, content, sizeBytes, mimeType);
    }

    @Override
    public ArtifactStorageReference storeForProcessingCycle(TenantId tenantId, PackageId packageId, int processingCycle,
                                                             String path, InputStream content,
                                                             long sizeBytes, String mimeType) {
        if (processingCycle < 1) throw new IllegalArgumentException("Processing cycle must be >= 1");
        UUID providerId = configuration.findPinnedProvider(tenantId, packageId, processingCycle)
                .orElseGet(() -> {
                    UUID selected = processingCycle == 1
                            ? configuration.inferCycleOneProviderFromExistingArtifacts(tenantId, packageId)
                                .orElseGet(() -> effectiveProvider(tenantId).id())
                            : effectiveProvider(tenantId).id();
                    return configuration.pinProvider(tenantId, packageId, processingCycle, selected, Instant.now());
                });
        ProcessingStorageProvider provider = configuration.findProvider(providerId)
                .orElseThrow(() -> storageFailure(false, "Pinned processing storage provider is unavailable", null));
        return storeOn(provider, path, content);
    }

    @Override
    public InputStream retrieve(ArtifactStorageReference reference) {
        ResolvedReference resolved = resolve(reference);
        Path targetFile = resolved.root().resolve(sanitizePath(reference.key())).normalize();
        guardWithinRoot(resolved.root(), targetFile);
        try {
            return Files.newInputStream(targetFile);
        } catch (IOException e) {
            throw storageFailure(true, "Failed to read artifact", e);
        }
    }

    @Override
    public void delete(ArtifactStorageReference reference) {
        ResolvedReference resolved = resolve(reference);
        Path targetFile = resolved.root().resolve(sanitizePath(reference.key())).normalize();
        guardWithinRoot(resolved.root(), targetFile);
        try {
            Files.deleteIfExists(targetFile);
        } catch (IOException e) {
            throw storageFailure(true, "Failed to delete artifact", e);
        }
    }

    private ArtifactStorageReference storeOn(ProcessingStorageProvider provider, String path, InputStream content) {
        ensureProviderSafeForCurrentTopology(provider);
        Path root = root(provider);
        ensureConfiguredRootExists(provider, root);
        String safePath = sanitizePath(path);
        Path targetFile = root.resolve(safePath).normalize();
        guardWithinRoot(root, targetFile);
        try {
            Files.createDirectories(targetFile.getParent());
            Files.copy(content, targetFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw storageFailure(true, "Failed to store artifact", e);
        }
        return ArtifactStorageReference.of(provider.id().toString(), provider.providerType().name(), safePath);
    }

    private ProcessingStorageProvider effectiveProvider(TenantId tenantId) {
        ProcessingStorageAssignment assignment = configuration.findTenantOverride(tenantId)
                .or(configuration::findDeploymentDefault)
                .orElseThrow(() -> storageFailure(false,
                        "Processing storage is not configured; Platform Operations setup is required", null));
        return configuration.findProvider(assignment.providerId())
                .filter(p -> p.status() != ProcessingStorageProviderStatus.RETIRED)
                .orElseThrow(() -> storageFailure(false, "Active processing storage provider is unavailable", null));
    }

    private ResolvedReference resolve(ArtifactStorageReference reference) {
        Objects.requireNonNull(reference, "Storage reference must not be null");
        if ("local".equalsIgnoreCase(reference.storageProvider())) {
            // Pre-028 compatibility. New writes never create this form.
            return new ResolvedReference(Path.of(legacyProperties.getRootPath()).toAbsolutePath().normalize());
        }
        UUID providerId;
        try {
            providerId = UUID.fromString(reference.storageProvider());
        } catch (IllegalArgumentException invalidProvider) {
            throw storageFailure(false, "Artifact references an unknown processing storage provider", invalidProvider);
        }
        ProcessingStorageProvider provider = configuration.findProvider(providerId)
                .orElseThrow(() -> storageFailure(false, "Artifact processing storage provider no longer exists", null));
        return new ResolvedReference(root(provider));
    }

    private void ensureProviderSafeForCurrentTopology(ProcessingStorageProvider provider) {
        if (provider.providerType() != ProcessingStorageProviderType.LOCAL_FILESYSTEM) return;
        long activeNodes = configuration.countActiveNodes(Instant.now().minus(ACTIVE_NODE_WINDOW));
        if (activeNodes > 1) {
            throw storageFailure(false,
                    "LOCAL_FILESYSTEM processing storage is not permitted while multiple application nodes are active; configure SHARED_FILESYSTEM",
                    null);
        }
    }

    private Path root(ProcessingStorageProvider provider) {
        return Path.of(provider.rootPath()).toAbsolutePath().normalize();
    }

    private void ensureConfiguredRootExists(ProcessingStorageProvider provider, Path root) {
        // Shared roots are infrastructure-owned and must never be recreated locally if a mount disappears.
        if (!Files.exists(root)) {
            throw storageFailure(true, provider.providerType() == ProcessingStorageProviderType.SHARED_FILESYSTEM
                    ? "Shared processing storage root is unavailable"
                    : "Local processing storage root is unavailable", null);
        }
        if (!Files.isDirectory(root) || !Files.isWritable(root)) {
            throw storageFailure(true, "Processing storage root is not writable", null);
        }
    }

    private void guardWithinRoot(Path root, Path targetFile) {
        if (!targetFile.startsWith(root)) throw new IllegalArgumentException("Path traversal detected");
    }

    private TechnicalDependencyException storageFailure(boolean retryable, String safeMessage, Throwable cause) {
        return new TechnicalDependencyException(
                TechnicalDependency.STORAGE, TechnicalFailureCode.STORAGE_IO, retryable, safeMessage, cause);
    }

    private String sanitizePath(String path) {
        String cleaned = path.replace('\\', '/');
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        if (cleaned.isBlank() || cleaned.contains("..")) {
            throw new IllegalArgumentException("Unsafe storage path");
        }
        return cleaned;
    }

    private record ResolvedReference(Path root) {}
}
