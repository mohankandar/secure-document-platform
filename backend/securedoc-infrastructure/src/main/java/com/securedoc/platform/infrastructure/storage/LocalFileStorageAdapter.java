package com.securedoc.platform.infrastructure.storage;

import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Local filesystem implementation of StoragePort.
 * Stores files under a configurable root directory using ID-based paths to prevent path traversal.
 */
@Component
public class LocalFileStorageAdapter implements StoragePort {

    private final Path rootPath;

    public LocalFileStorageAdapter(LocalStorageProperties properties) {
        this.rootPath = Path.of(properties.getRootPath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create storage root: " + rootPath, e);
        }
    }

    @Override
    public ArtifactStorageReference store(TenantId tenantId, String path,
                                           InputStream content, long sizeBytes, String mimeType) {
        // Sanitize path: only allow safe characters (IDs and slashes)
        String safePath = sanitizePath(path);
        Path targetFile = rootPath.resolve(safePath).normalize();

        // Prevent path traversal
        if (!targetFile.startsWith(rootPath)) {
            throw new IllegalArgumentException("Path traversal detected: " + path);
        }

        try {
            Files.createDirectories(targetFile.getParent());
            Files.copy(content, targetFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file: " + safePath, e);
        }

        return ArtifactStorageReference.of("local", rootPath.toString(), safePath);
    }

    @Override
    public InputStream retrieve(ArtifactStorageReference reference) {
        Path targetFile = rootPath.resolve(reference.key()).normalize();
        if (!targetFile.startsWith(rootPath)) {
            throw new IllegalArgumentException("Path traversal detected");
        }
        try {
            return Files.newInputStream(targetFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read file: " + reference.key(), e);
        }
    }

    @Override
    public void delete(ArtifactStorageReference reference) {
        Path targetFile = rootPath.resolve(reference.key()).normalize();
        if (!targetFile.startsWith(rootPath)) {
            throw new IllegalArgumentException("Path traversal detected");
        }
        try {
            Files.deleteIfExists(targetFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete file: " + reference.key(), e);
        }
    }

    private String sanitizePath(String path) {
        // Remove any leading slash and replace backslashes
        String cleaned = path.replace('\\', '/');
        if (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        // Block ".." segments
        if (cleaned.contains("..")) {
            throw new IllegalArgumentException("Path must not contain '..': " + path);
        }
        return cleaned;
    }
}
