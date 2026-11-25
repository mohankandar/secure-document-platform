package com.securedoc.platform.infrastructure.delivery;

import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>Task 0086: a server killed after the folder was published and before the delivery was recorded leaves a finished
 * folder. The next try takes it as this delivery only when it is exactly that ({@link #holdsExactly}); anything else is
 * LV-DLV-010 and not touched. The documents are acknowledged together by the result, never one by one: the export service
 * saves each single acknowledgement and would ask only for the others next time, which no folder could be compared with.
 */
@Component
@ConditionalOnProperty(name = "securedoc.delivery.type", havingValue = "local")
public class LocalFolderDeliveryAdapter implements DeliveryPort {

    private static final Logger log = LoggerFactory.getLogger(LocalFolderDeliveryAdapter.class);
    private static final String DESTINATION_TYPE = "local-folder";
    private static final String STAGING_DIRECTORY = ".staging";
    private static final int COMPARE_BLOCK = 64 * 1024;

    private final Path outputRoot;

    public LocalFolderDeliveryAdapter(LocalDeliveryProperties properties) {
        this.outputRoot = Paths.get(properties.getOutputPath()).toAbsolutePath().normalize();
    }

    @Override
    public DeliveryDescriptor describe(TenantId tenantId) {
        return new DeliveryDescriptor(DESTINATION_TYPE, null, null);
    }

    /**
     * Task 0028: the local folder's health check. A small test file is written under the tenant's staging folder and
     * deleted again; nothing of a case is touched. Fails (LV-DLV-001) when the folder can't be written.
     */
    @Override
    public void checkDestination(TenantId tenantId) {
        Path tenantDir = safeChild(outputRoot, tenantId.value().toString(), "tenant");
        Path stagingRoot = safeChild(tenantDir, STAGING_DIRECTORY, "staging root");
        Path probe = safeChild(stagingRoot, "lexavault-check-" + UUID.randomUUID() + ".tmp", "check file");
        try {
            Files.createDirectories(stagingRoot);
            Files.write(probe, new byte[] {'o', 'k'});
            Files.delete(probe);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException ignored) {
                // The check failed already; a left-over check file holds no case data.
            }
            throw new TechnicalDependencyException(TechnicalDependency.DELIVERY, TechnicalFailureCode.DELIVERY_IO,
                    true, "Local export folder can't be written", e)
                    .withLvCode(com.securedoc.platform.domain.error.LvErrorCode.DLV_001);
        }
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

        // Each document's folder and the one file in it.
        Map<String, String> expected = new HashMap<>();
        List<DeliveryReference> finalReferences = new ArrayList<>(items.size());
        for (DeliveryItem item : items) {
            String documentFolder = item.documentId().value().toString();
            String safeName = sanitizeFileName(item.originalFileName());
            safeChild(safeChild(finalPackageDir, documentFolder, "document"), safeName, "delivery file");
            expected.put(documentFolder, safeName);
            // Task 0023 (B4, #200/#201): the recorded location is relative and names no file:
            // no absolute path and no original file name in the export record or any response.
            finalReferences.add(new DeliveryReference(
                    item.documentId(), item.sourceDocumentId(), DESTINATION_TYPE, null,
                    recordedLocation(packageId, exportCycle, item.documentId())));
        }

        if (isThere(finalPackageDir)) return ownFinishedDelivery(batch, finalPackageDir, expected, finalReferences);
        try {
            Files.createDirectories(stagingPackageDir);
            for (DeliveryItem item : items) {
                String documentFolder = item.documentId().value().toString();
                Path stagingDocumentDir = safeChild(stagingPackageDir, documentFolder, "document");
                Path stagedFile = safeChild(stagingDocumentDir, expected.get(documentFolder), "delivery file");
                Files.createDirectories(stagingDocumentDir);
                try (InputStream content = open(item)) {
                    Files.copy(content, stagedFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            Files.createDirectories(packageDir);
            // Without options the move never replaces: an atomic one puts the folder in place of an empty folder (Linux) or
            // of a file (Windows) that appeared meanwhile. Staging is on the same disk, so it is one rename either way.
            Files.move(stagingPackageDir, finalPackageDir);
        } catch (IOException e) {
            deleteStagingBestEffort(stagingPackageDir, packageId, exportCycle);
            // Another server published this case run first.
            if (isThere(finalPackageDir)) return ownFinishedDelivery(batch, finalPackageDir, expected, finalReferences);
            throw ioFailure(e);
        } catch (RuntimeException e) {
            deleteStagingBestEffort(stagingPackageDir, packageId, exportCycle);
            throw e;
        }
        return new BatchDeliveryReference(DESTINATION_TYPE, null, finalReferences);
    }

    /** Also a link that points nowhere. */
    private static boolean isThere(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static InputStream open(DeliveryItem item) {
        InputStream content = item.contentSource().open();
        if (content == null) throw new IllegalStateException("Delivery content source returned null");
        return content;
    }

    private static TechnicalDependencyException ioFailure(IOException e) {
        return new TechnicalDependencyException(TechnicalDependency.DELIVERY, TechnicalFailureCode.DELIVERY_IO, true, "Failed to deliver package to local folder", e);
    }

    /** The folder that is there is this delivery's own finished one (a success, audited with LV-DLV-009), or LV-DLV-010. */
    private BatchDeliveryReference ownFinishedDelivery(DeliveryBatch batch, Path folder, Map<String, String> expected,
                                                       List<DeliveryReference> references) {
        boolean same;
        try {
            same = holdsExactly(folder, batch.items(), expected);
        } catch (IOException e) {
            throw ioFailure(e);
        }
        if (!same) {
            throw new TechnicalDependencyException(TechnicalDependency.DELIVERY, TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false, "Package delivery destination already exists").withLvCode(com.securedoc.platform.domain.error.LvErrorCode.DLV_010);
        }
        log.info("LOCAL_DELIVERY_ALREADY_PUBLISHED package={} cycle={} documents={}", batch.packageId().value(),
                batch.exportCycle(), references.size());
        return new BatchDeliveryReference(DESTINATION_TYPE, null, references, true);
    }

    /**
     * Whether the folder holds exactly these documents: real folders and regular files only (no links), no more and no
     * fewer, the names as they are stored (capitals count), and every file byte for byte its document's content.
     */
    private static boolean holdsExactly(Path folder, List<DeliveryItem> items, Map<String, String> expected) throws IOException {
        if (!isRealFolder(folder) || !names(folder).equals(expected.keySet())) return false;
        for (Map.Entry<String, String> document : expected.entrySet()) {
            Path documentFolder = folder.resolve(document.getKey());
            if (!isRealFolder(documentFolder) || !names(documentFolder).equals(Set.of(document.getValue()))
                    || !attributes(documentFolder.resolve(document.getValue())).isRegularFile()) return false;
        }
        for (DeliveryItem item : items) {
            String documentFolder = item.documentId().value().toString();
            Path file = folder.resolve(documentFolder).resolve(expected.get(documentFolder));
            try (InputStream content = open(item); InputStream stored = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!sameBytes(content, stored)) return false;
            }
        }
        return true;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    /** A Windows junction counts as a folder of another kind ({@code isOther}). */
    private static boolean isRealFolder(Path path) throws IOException {
        BasicFileAttributes attributes = attributes(path);
        return attributes.isDirectory() && !attributes.isSymbolicLink() && !attributes.isOther();
    }

    private static Set<String> names(Path folder) throws IOException {
        Set<String> names = new HashSet<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(folder)) {
            for (Path child : children) names.add(child.getFileName().toString());
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }
        return names;
    }

    private static boolean sameBytes(InputStream a, InputStream b) throws IOException {
        byte[] blockA = new byte[COMPARE_BLOCK];
        byte[] blockB = new byte[COMPARE_BLOCK];
        while (true) {
            int readA = a.readNBytes(blockA, 0, COMPARE_BLOCK);
            int readB = b.readNBytes(blockB, 0, COMPARE_BLOCK);
            if (readA != readB || !Arrays.equals(blockA, 0, readA, blockB, 0, readB)) return false;
            if (readA < COMPARE_BLOCK) return true;
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

    /** The relative location kept in the delivery and export records: ids only, no root path and no file name. */
    static String recordedLocation(PackageId packageId, int exportCycle, com.securedoc.platform.domain.identity.DocumentId documentId) {
        return DESTINATION_TYPE + ":" + packageId.value() + "/cycle-" + exportCycle + "/" + documentId.value();
    }

    /**
     * Best-effort cleanup of private staging. A failed delete is logged (task 0023 #200) with the package id, the
     * cycle and the count only: the staging folder holds redacted output, so IT can find it by those ids.
     */
    private void deleteStagingBestEffort(Path root, PackageId packageId, int exportCycle) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        int[] failed = {0};
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    failed[0]++;
                }
            });
        } catch (IOException e) {
            failed[0]++;
        }
        if (failed[0] > 0) {
            log.warn("LOCAL_DELIVERY_STAGING_CLEANUP_FAILED package={} cycle={} failedEntries={}",
                    packageId.value(), exportCycle, failed[0]);
        }
    }
}
