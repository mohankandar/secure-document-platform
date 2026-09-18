package com.securedoc.platform.api.upload;

import com.securedoc.platform.application.intake.command.UploadContent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Repeatable, disk-backed UploadContent for large raw request bodies.
 *
 * <p>The request is streamed to a temp file with a hard byte ceiling, so scanner packages do not
 * depend on servlet multipart limits and are never buffered wholesale in JVM memory.</p>
 */
public final class RequestBodyUploadContent implements UploadContent, AutoCloseable {
    private final Path tempFile;
    private final long contentLength;

    private RequestBodyUploadContent(Path tempFile, long contentLength) {
        this.tempFile = tempFile;
        this.contentLength = contentLength;
    }

    public static RequestBodyUploadContent from(InputStream input, long declaredLength, long maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("Maximum upload size must be positive");
        if (declaredLength > maxBytes) throw new IllegalArgumentException("Upload exceeds absolute scanner intake size limit");
        Path temp = null;
        try {
            temp = Files.createTempFile("securedoc-scanner-upload-", ".tmp");
            long total = 0;
            byte[] buffer = new byte[1024 * 1024];
            try (var output = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) throw new IllegalArgumentException("Upload exceeds absolute scanner intake size limit");
                    output.write(buffer, 0, read);
                }
                output.flush();
            }
            if (declaredLength >= 0 && declaredLength != total) {
                throw new IllegalArgumentException("Scanner upload content length did not match the request body");
            }
            return new RequestBodyUploadContent(temp, total);
        } catch (RuntimeException ex) {
            deleteQuietly(temp);
            throw ex;
        } catch (IOException ex) {
            deleteQuietly(temp);
            throw new UncheckedIOException("Failed to spool scanner upload", ex);
        }
    }

    @Override
    public InputStream openStream() {
        try {
            return Files.newInputStream(tempFile);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to open scanner upload", ex);
        }
    }

    @Override
    public long contentLength() {
        return contentLength;
    }

    @Override
    public void close() {
        deleteQuietly(tempFile);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }
}
