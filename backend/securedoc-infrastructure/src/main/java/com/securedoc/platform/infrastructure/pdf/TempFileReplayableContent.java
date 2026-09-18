package com.securedoc.platform.infrastructure.pdf;

import com.securedoc.platform.application.port.out.ReplayableContent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Disk-backed replayable content with deterministic temporary-file cleanup. */
public final class TempFileReplayableContent implements ReplayableContent {
    private final Path path;
    private final long contentLength;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private TempFileReplayableContent(Path path, long contentLength) {
        this.path = Objects.requireNonNull(path);
        this.contentLength = contentLength;
    }

    public static TempFileReplayableContent owned(Path path) {
        Objects.requireNonNull(path);
        try {
            long size = Files.size(path);
            if (size <= 0) {
                Files.deleteIfExists(path);
                throw new IllegalArgumentException("Generated PDF content must not be empty");
            }
            return new TempFileReplayableContent(path, size);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to inspect temporary PDF content", e);
        }
    }

    @Override
    public InputStream openStream() {
        if (closed.get()) {
            throw new IllegalStateException("Replayable content is already closed");
        }
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to open temporary PDF content", e);
        }
    }

    @Override
    public long contentLength() {
        return contentLength;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to delete temporary PDF content", e);
        }
    }
}
