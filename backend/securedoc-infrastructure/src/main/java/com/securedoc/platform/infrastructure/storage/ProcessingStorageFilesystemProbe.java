package com.securedoc.platform.infrastructure.storage;

import com.securedoc.platform.domain.storage.ProcessingStorageProvider;
import com.securedoc.platform.domain.storage.ProcessingStorageValidationStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/** Safe filesystem probe. It never creates the configured root itself. */
@Component
public class ProcessingStorageFilesystemProbe {

    public ProbeResult validate(ProcessingStorageProvider provider, String nodeId, UUID processInstanceId) {
        Path root;
        try {
            root = Path.of(provider.rootPath()).toAbsolutePath().normalize();
        } catch (Exception invalidPath) {
            return new ProbeResult(ProcessingStorageValidationStatus.UNHEALTHY, "INVALID_PATH", Instant.now());
        }
        if (!Files.exists(root)) return new ProbeResult(ProcessingStorageValidationStatus.UNHEALTHY, "ROOT_NOT_FOUND", Instant.now());
        if (!Files.isDirectory(root)) return new ProbeResult(ProcessingStorageValidationStatus.UNHEALTHY, "ROOT_NOT_DIRECTORY", Instant.now());
        if (!Files.isReadable(root)) return new ProbeResult(ProcessingStorageValidationStatus.UNHEALTHY, "ROOT_NOT_READABLE", Instant.now());
        if (!Files.isWritable(root)) return new ProbeResult(ProcessingStorageValidationStatus.UNHEALTHY, "ROOT_NOT_WRITABLE", Instant.now());

        Path probeFile = null;
        try {
            String safeNode = nodeId.replaceAll("[^A-Za-z0-9._-]", "_");
            Path probeDir = root.resolve(".lexavault-health").resolve(safeNode).resolve(processInstanceId.toString()).normalize();
            if (!probeDir.startsWith(root)) return new ProbeResult(ProcessingStorageValidationStatus.UNHEALTHY, "PROBE_PATH_REJECTED", Instant.now());
            Files.createDirectories(probeDir);
            probeFile = probeDir.resolve("probe-" + UUID.randomUUID() + ".tmp");
            byte[] marker = ("lexavault-storage-probe:" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
            Files.write(probeFile, marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            byte[] readBack = Files.readAllBytes(probeFile);
            if (!java.util.Arrays.equals(marker, readBack)) {
                return new ProbeResult(ProcessingStorageValidationStatus.UNHEALTHY, "PROBE_READ_MISMATCH", Instant.now());
            }
            Files.delete(probeFile);
            probeFile = null;
            return new ProbeResult(ProcessingStorageValidationStatus.HEALTHY, null, Instant.now());
        } catch (Exception probeFailure) {
            return new ProbeResult(ProcessingStorageValidationStatus.UNHEALTHY, "PROBE_IO_FAILED", Instant.now());
        } finally {
            if (probeFile != null) {
                try { Files.deleteIfExists(probeFile); } catch (Exception ignored) { }
            }
        }
    }

    public record ProbeResult(ProcessingStorageValidationStatus status, String failureCode, Instant checkedAt) {}
}
