package com.securedoc.platform.application.intake.polling;

import com.securedoc.platform.application.intake.command.UploadContent;

import java.util.Objects;

/** Temporary connector-owned retrieval staged outside the canonical storage area. */
public record RetrievedPollingDocument(String mimeType, String sha256, UploadContent content, AutoCloseable cleanup) implements AutoCloseable {
    public RetrievedPollingDocument {
        Objects.requireNonNull(mimeType); Objects.requireNonNull(sha256); Objects.requireNonNull(content); Objects.requireNonNull(cleanup);
    }
    @Override public void close() {
        try { cleanup.close(); } catch (Exception ignored) { }
    }
}
