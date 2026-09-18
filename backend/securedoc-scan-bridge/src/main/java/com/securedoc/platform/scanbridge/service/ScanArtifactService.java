package com.securedoc.platform.scanbridge.service;

import com.securedoc.platform.scanbridge.model.PreparedScan;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves and disposes bridge-owned PDF artifacts without allowing path traversal. */
@Component
public class ScanArtifactService {
    private final FileScanSessionStore store;

    public ScanArtifactService(FileScanSessionStore store) {
        this.store = store;
    }

    public Path resolve(PreparedScan preparedScan) {
        if (preparedScan == null || preparedScan.workReference() == null || preparedScan.workReference().isBlank()) {
            throw new IllegalArgumentException("Prepared scan has no durable work reference");
        }
        String portable = preparedScan.workReference().replace('/', java.io.File.separatorChar);
        Path resolved = store.workRoot().resolve(portable).normalize();
        if (!resolved.startsWith(store.workRoot())) {
            throw new IllegalArgumentException("Prepared scan work reference escapes the bridge work root");
        }
        return resolved;
    }

    public boolean exists(PreparedScan preparedScan) {
        return preparedScan != null && preparedScan.workReference() != null && !preparedScan.workReference().isBlank()
                && Files.isRegularFile(resolve(preparedScan));
    }

    public void deletePdfIfPresent(PreparedScan preparedScan) {
        if (preparedScan == null || preparedScan.workReference() == null || preparedScan.workReference().isBlank()) return;
        try {
            Files.deleteIfExists(resolve(preparedScan));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to dispose the locally accepted Scanner Intake PDF", ex);
        }
    }
}
