package com.securedoc.platform.infrastructure.delivery;

import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Local development delivery destination with package-atomic publication.
 *
 * <p>All documents are first written under a private staging directory. Only after every write
 * succeeds is the complete package directory moved into its final visible location. A failed
 * write removes staging and leaves no partial final package.
 *
 * <p>Example: {@code application.pdf} →
 * {@code securedoc-output/{tenant}/{packageId}/cycle-{n}/{documentId}/application.pdf}.
 */
@Component
@ConditionalOnProperty(name = "securedoc.delivery.type", havingValue = "local")
public class LocalFolderDeliveryAdapter implements DeliveryPort {

    private static final String DESTINATION_TYPE = "local-folder";
    private static final String STAGING_DIRECTORY = ".staging";

    private final Path outputRoot;

    public LocalFolderDeliveryAdapter(LocalDeliveryProperties properties) {
        this.outputRoot = Paths.get(properties.getOutputPath()).toAbsolutePath().normalize();
    }

    @Override
    public DeliveryDescriptor describe(TenantId tenantId) {
        return new DeliveryDescriptor(DESTINATION_TYPE, null, null);
    }

    @Override
    public BatchDeliveryReference deliverBatch(DeliveryBatch batch) {
        TenantId tenantId = batch.tenantId();
        PackageId packageId = batch.packageId();
        int exportCycle = batch.exportCycle();
        List<DeliveryItem> items = batch.items();
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Delivery batch must contain at least one document");
        }
        if (exportCycle < 1) {
            throw new IllegalArgumentException("Export cycle must be >= 1");
        }

        Path tenantDir = safeChild(outputRoot, tenantId.value().toString(), "tenant");
        Path packageDir = safeChild(tenantDir, packageId.value().toString(), "package");
        Path finalPackageDir = safeChild(packageDir, "cycle-" + exportCycle, "export cycle");
        Path stagingRoot = safeChild(tenantDir, STAGING_DIRECTORY, "staging root");
        Path stagingPackageDir = safeChild(stagingRoot,
                packageId.value() + "-cycle-" + exportCycle + "-" + UUID.randomUUID(), "staging package");

        if (Files.exists(finalPackageDir)) {
            throw new TechnicalDependencyException(TechnicalDependency.DELIVERY, TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false, "Package delivery destination already exists");
        }

        List<DeliveryReference> finalReferences = new ArrayList<>(items.size());
        try {
            Files.createDirectories(stagingPackageDir);
            for (DeliveryItem item : items) {
                String safeName = sanitizeFileName(item.originalFileName());
                Path stagingDocumentDir = safeChild(stagingPackageDir,
                        item.documentId().value().toString(), "document");
                Path stagedFile = safeChild(stagingDocumentDir, safeName, "delivery file");
                Files.createDirectories(stagingDocumentDir);
                try (InputStream content = item.contentSource().open()) {
                    if (content == null) {
                        throw new IllegalStateException("Delivery content source returned null");
                    }
                    Files.copy(content, stagedFile, StandardCopyOption.REPLACE_EXISTING);
                }

                Path finalFile = finalPackageDir
                        .resolve(item.documentId().value().toString())
                        .resolve(safeName)
                        .normalize();
                if (!finalFile.startsWith(finalPackageDir)) {
                    throw new IllegalArgumentException("Resolved final delivery file escapes package directory");
                }
                finalReferences.add(new DeliveryReference(
                        item.documentId(), item.sourceDocumentId(), DESTINATION_TYPE, null, finalFile.toString()));
            }

            Files.createDirectories(packageDir);
            publishAtomically(stagingPackageDir, finalPackageDir);
            for (DeliveryReference reference : finalReferences) {
                batch.progressListener().documentAcknowledged(reference);
            }
            return new BatchDeliveryReference(DESTINATION_TYPE, null, finalReferences);
        } catch (IOException e) {
            deleteRecursivelyQuietly(stagingPackageDir);
            throw new TechnicalDependencyException(TechnicalDependency.DELIVERY, TechnicalFailureCode.DELIVERY_IO, true, "Failed to deliver package to local folder", e);
        } catch (RuntimeException e) {
            deleteRecursivelyQuietly(stagingPackageDir);
            throw e;
        }
    }

    private void publishAtomically(Path stagedPackage, Path finalPackage) throws IOException {
        try {
            Files.move(stagedPackage, finalPackage, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Same-filesystem directory rename is still the publication boundary on platforms that
            // do not advertise ATOMIC_MOVE. No individual document is copied into finalPackage.
            Files.move(stagedPackage, finalPackage);
        }
    }

    private Path safeChild(Path parent, String child, String label) {
        Path resolved = parent.resolve(child).normalize();
        if (!resolved.startsWith(parent)) {
            throw new IllegalArgumentException("Resolved " + label + " path escapes its parent");
        }
        return resolved;
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Original filename must not be blank");
        }
        String name = fileName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isBlank() || name.contains("..")) {
            throw new IllegalArgumentException("Invalid original filename");
        }
        return name;
    }

    private void deleteRecursivelyQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort cleanup of private staging only.
                }
            });
        } catch (IOException ignored) {
            // Best effort cleanup of private staging only.
        }
    }
}
