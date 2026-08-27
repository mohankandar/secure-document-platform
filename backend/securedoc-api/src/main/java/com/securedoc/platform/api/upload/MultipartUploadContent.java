package com.securedoc.platform.api.upload;

import com.securedoc.platform.application.intake.command.UploadContent;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * API-layer implementation of UploadContent backed by a temp file.
 * Copies multipart content to a temp file so openStream() can be called multiple times.
 * Temp file is cleaned up via close().
 *
 * <p>This is local/demo only. Production may use a more robust approach.
 */
public final class MultipartUploadContent implements UploadContent, AutoCloseable {

    private final Path tempFile;
    private final long contentLength;

    private MultipartUploadContent(Path tempFile, long contentLength) {
        this.tempFile = tempFile;
        this.contentLength = contentLength;
    }

    /**
     * Creates an UploadContent from a MultipartFile by copying to a temp file.
     */
    public static MultipartUploadContent from(MultipartFile file) {
        Objects.requireNonNull(file, "MultipartFile must not be null");
        try {
            Path temp = Files.createTempFile("securedoc-upload-", ".tmp");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            return new MultipartUploadContent(temp, file.getSize());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to buffer upload content", e);
        }
    }

    @Override
    public InputStream openStream() {
        try {
            return Files.newInputStream(tempFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open upload content stream", e);
        }
    }

    @Override
    public long contentLength() {
        return contentLength;
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            // Best effort cleanup
        }
    }
}
